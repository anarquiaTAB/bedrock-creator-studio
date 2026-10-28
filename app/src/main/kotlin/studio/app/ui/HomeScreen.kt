package studio.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import studio.app.Io
import studio.core.project.ProjectMeta
import studio.core.project.ProjectStore

private fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: ProjectStore, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var projects by remember { mutableStateOf(emptyList<ProjectMeta>()) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ProjectMeta?>(null) }
    fun refresh() { projects = runCatching { store.list() }.getOrElse { ctx.toast("Erro ao listar projetos: ${it.message}"); emptyList() } }
    LaunchedEffect(Unit) { refresh() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val tmp = java.io.File(ctx.cacheDir, "import.tmp")
            Io.copyUriToFile(ctx, uri, tmp)
            store.importArchive(tmp.toPath())
            tmp.delete()
            refresh()
            ctx.toast("Projeto importado.")
        } catch (e: Exception) {
            ctx.toast("Não foi possível importar: ${e.message}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Bedrock Creator Studio") }, actions = {
                IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, "Importar pacote ou backup") }
            })
        },
        floatingActionButton = { FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Novo projeto") } },
    ) { pad ->
        if (projects.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(24.dp)) { Text("Nenhum projeto ainda. Toque em + para criar ou importe um .mcpack, .mcaddon ou .zip.") }
        } else LazyColumn(Modifier.padding(pad)) {
            items(projects, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text(p.targetMinecraft?.let { "Minecraft $it" } ?: "Versão-alvo não definida") },
                    trailingContent = { IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, "Excluir") } },
                    modifier = Modifier.clickable { onOpen(p.id) },
                )
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        var version by remember { mutableStateOf("1.21.0") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Novo projeto") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(version, { version = it }, label = { Text("Versão do Minecraft (alvo)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try { store.create(name.trim(), version.trim().ifBlank { null }); creating = false; refresh() }
                    catch (e: Exception) { ctx.toast(e.message ?: "Erro ao criar o projeto") }
                }) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Excluir '${p.name}'?") },
            text = { Text("Isto apaga o projeto do aparelho e não pode ser desfeito. Exporte um backup antes se precisar.") },
            confirmButton = { TextButton(onClick = { runCatching { store.delete(p.id) }.onFailure { ctx.toast(it.message ?: "Erro") }; deleting = null; refresh() }) { Text("Excluir") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
        )
    }
}
