package studio.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import studio.core.json.Json
import studio.core.json.JsonParseException
import studio.core.project.ProjectStore

/** Editor de texto simples (sem destaque de sintaxe ainda): valida JSON com linha/coluna, grava rascunho e salva de forma atômica. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(store: ProjectStore, id: String, file: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val project = remember(id) { runCatching { store.open(id).project }.getOrNull() }
    var text by remember { mutableStateOf(project?.let { runCatching { it.readDraft(file) ?: it.readText(file) }.getOrNull() } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(text) {
        error = if (file.endsWith(".json")) try { Json.parse(text); null } catch (e: JsonParseException) { e.message } else null
        runCatching { project?.saveDraft(file, text) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(file) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
            actions = {
                IconButton(onClick = {
                    try { project!!.commitDraft(file); Toast.makeText(ctx, "Salvo.", Toast.LENGTH_SHORT).show() }
                    catch (e: Exception) { Toast.makeText(ctx, "Falha ao salvar: ${e.message}", Toast.LENGTH_LONG).show() }
                }) { Icon(Icons.Default.Save, "Salvar") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            error?.let { Text("JSON inválido: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxSize().padding(8.dp), textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace))
        }
    }
}
