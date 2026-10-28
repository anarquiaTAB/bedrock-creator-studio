package studio.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import studio.app.Io
import studio.core.diagnostics.DiagnosticReport
import studio.core.pack.Packager
import studio.core.project.Project
import studio.core.project.ProjectStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(store: ProjectStore, id: String, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val ctx = LocalContext.current
    var project by remember { mutableStateOf<Project?>(null) }
    var files by remember { mutableStateOf(emptyList<String>()) }
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var pendingExport by remember { mutableStateOf<java.io.File?>(null) }
    fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    LaunchedEffect(id) {
        try {
            val r = store.open(id)
            project = r.project
            files = r.project.listFiles()
            report = r.project.diagnose()
            if (r.recovery.pendingDrafts.isNotEmpty()) toast("Há ${r.recovery.pendingDrafts.size} rascunho(s) não salvo(s) de uma sessão anterior.")
        } catch (e: Exception) { toast("Erro ao abrir o projeto: ${e.message}") }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = pendingExport
        if (uri != null && f != null) runCatching { Io.copyFileToUri(ctx, f, uri) }.onSuccess { toast("Arquivo salvo.") }.onFailure { toast("Falha ao salvar: ${it.message}") }
    }

    /** Exporta .mcaddon para o cache (com pré-validação e verificação final do núcleo); depois salva ou compartilha. */
    fun export(share: Boolean) {
        val p = project ?: return
        val out = java.io.File(Io.exportsDir(ctx), "${p.meta.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.mcaddon")
        out.delete()
        val res = Packager.exportMcaddon(p.packDirs(), out.toPath())
        report = res.report
        if (res.output == null) { toast("Exportação recusada: ${res.report.errors.firstOrNull()?.message ?: "veja os problemas na lista"}"); return }
        if (share) {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
            val i = Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Compartilhar .mcaddon"))
        } else { pendingExport = out; saver.launch(out.name) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(project?.meta?.name ?: "Projeto") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            actions = {
                IconButton(onClick = { export(false) }) { Icon(Icons.Default.Save, "Salvar .mcaddon") }
                IconButton(onClick = { export(true) }) { Icon(Icons.Default.Share, "Compartilhar .mcaddon") }
            },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            report?.let { r ->
                item {
                    Text(
                        "${r.errors.size} erro(s), ${r.warnings.size} aviso(s). Estas verificações são locais; o conteúdo só é confirmado ao testar no Minecraft.",
                        Modifier.padding(16.dp),
                    )
                }
                items(r.items.filter { it.severity != studio.core.diagnostics.Severity.INFO }) { d ->
                    ListItem(headlineContent = { Text(d.message) }, supportingContent = { Text("${d.severity} · ${d.code}${d.file?.let { " · $it" } ?: ""}") })
                }
            }
            item { HorizontalDivider() }
            items(files) { f -> ListItem(headlineContent = { Text(f) }, modifier = Modifier.clickable { if (f.endsWith(".json") || f.endsWith(".js") || f.endsWith(".mcfunction") || f.endsWith(".lang") || f.endsWith(".txt")) onEdit(f) else toast("Este tipo de arquivo ainda não abre no editor de texto.") }) }
        }
    }
}
