package studio.app

import android.content.Context
import android.net.Uri
import java.io.File

/** Cópia entre Uris do Android (seletor de documentos) e arquivos locais. Nada de acesso irrestrito a pastas de outros apps. */
object Io {
    fun copyUriToFile(ctx: Context, uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Não foi possível abrir o arquivo escolhido." }
            dest.outputStream().use { input.copyTo(it) }
        }
    }

    fun copyFileToUri(ctx: Context, src: File, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri, "wt").use { out ->
            requireNotNull(out) { "Não foi possível gravar no local escolhido." }
            src.inputStream().use { it.copyTo(out) }
        }
    }

    fun exportsDir(ctx: Context): File = File(ctx.cacheDir, "exports").apply { mkdirs() }
}
