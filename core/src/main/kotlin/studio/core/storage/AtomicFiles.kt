package studio.core.storage

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class StorageException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Converte IOException em StorageException com mensagem em português. */
inline fun <T> io(what: String, block: () -> T): T =
    try {
        block()
    } catch (e: IOException) {
        throw StorageException("$what: ${e.message ?: e.javaClass.simpleName}", e)
    }

object AtomicFiles {
    const val TMP_SUFFIX = ".studio-tmp"

    fun writeText(target: Path, text: String) = writeBytes(target, text.toByteArray(Charsets.UTF_8))

    fun writeBytes(target: Path, bytes: ByteArray) = writeWith(target) { it.write(bytes) }

    /**
     * Grava em arquivo temporário vizinho, força a escrita em disco e só então
     * substitui o destino. Se algo falhar, o arquivo original permanece intacto.
     * O [block] NÃO deve fechar o stream recebido.
     */
    fun writeWith(target: Path, block: (OutputStream) -> Unit) {
        io("Falha ao gravar '${target.fileName}'") {
            val abs = target.toAbsolutePath()
            val parent = abs.parent
            Files.createDirectories(parent)
            val prefix = "." + abs.fileName.toString().take(40) + "."
            val tmp = Files.createTempFile(parent, prefix, TMP_SUFFIX)
            try {
                FileChannel.open(tmp, StandardOpenOption.WRITE).use { ch ->
                    val os = BufferedOutputStream(Channels.newOutputStream(ch))
                    block(os)
                    os.flush()
                    ch.force(true)
                }
                moveReplacing(tmp, abs)
            } catch (t: Throwable) {
                try {
                    Files.deleteIfExists(tmp)
                } catch (_: IOException) {
                }
                throw t
            }
        }
    }

    private fun moveReplacing(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Remove temporários deixados por gravações interrompidas. Retorna quantos foram removidos. */
    fun cleanupStale(dir: Path): Int {
        if (!Files.isDirectory(dir)) return 0
        var removed = 0
        for (p in walkFiles(dir)) {
            if (p.fileName.toString().endsWith(TMP_SUFFIX)) {
                if (Files.deleteIfExists(p)) removed++
            }
        }
        return removed
    }
}
