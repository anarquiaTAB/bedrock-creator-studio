package studio.core.archive

import studio.core.storage.AtomicFiles
import studio.core.storage.io
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Arquivo ZIP inválido, corrompido ou perigoso (zip-slip, bomba de descompressão etc.). */
class ArchiveException(message: String) : RuntimeException(message)

object SafeZip {
    data class Limits(
        val maxEntries: Int = 50_000,
        val maxEntryBytes: Long = 512L * 1024 * 1024,
        val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    )

    private val DRIVE = Regex("^[A-Za-z]:")

    /** Normaliza o nome de uma entrada; lança [ArchiveException] se for absoluto ou sair do destino. */
    fun normalizeEntryName(raw: String): String {
        if (raw.contains('\u0000')) throw ArchiveException("Nome de entrada inválido")
        val n = raw.replace('\\', '/')
        if (n.startsWith("/") || DRIVE.containsMatchIn(n)) {
            throw ArchiveException("Caminho absoluto não permitido no ZIP: $raw")
        }
        val segments = n.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw ArchiveException("Caminho com '..' não permitido no ZIP: $raw")
        return segments.joinToString("/")
    }

    /** Extrai [zip] em [dest]. Retorna os nomes (relativos) dos arquivos extraídos. */
    fun extract(zip: Path, dest: Path, limits: Limits = Limits()): List<String> {
        io("Falha ao preparar a pasta de destino") { Files.createDirectories(dest) }
        val destReal = io("Falha ao ler a pasta de destino") { dest.toRealPath() }
        val extracted = mutableListOf<String>()
        val seen = HashSet<String>()
        var total = 0L
        var count = 0
        try {
            ZipFile(zip.toFile()).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    count++
                    if (count > limits.maxEntries) throw ArchiveException("O arquivo tem entradas demais")
                    val name = normalizeEntryName(e.name)
                    if (name.isEmpty()) continue
                    val target = destReal.resolve(name).normalize()
                    if (!target.startsWith(destReal)) throw ArchiveException("Entrada fora do destino: ${e.name}")
                    if (e.isDirectory) {
                        Files.createDirectories(target)
                        continue
                    }
                    if (!seen.add(name)) throw ArchiveException("Entrada duplicada no ZIP: $name")
                    Files.createDirectories(target.parent)
                    var entryBytes = 0L
                    zf.getInputStream(e).use { input ->
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { out ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                entryBytes += n
                                total += n
                                if (entryBytes > limits.maxEntryBytes || total > limits.maxTotalBytes) {
                                    throw ArchiveException("Tamanho descompactado excede o limite permitido")
                                }
                                out.write(buf, 0, n)
                            }
                        }
                    }
                    extracted.add(name)
                }
            }
        } catch (e: ZipException) {
            throw ArchiveException("ZIP inválido ou corrompido: ${e.message}")
        } catch (e: java.io.IOException) {
            throw studio.core.storage.StorageException("Falha ao extrair o arquivo: ${e.message}", e)
        }
        return extracted
    }

    /** Cria um ZIP (de forma atômica) com as entradas (nome no ZIP -> arquivo em disco). */
    fun createFromEntries(out: Path, entries: List<Pair<String, Path>>) {
        val names = HashSet<String>()
        for ((name, _) in entries) {
            val norm = normalizeEntryName(name)
            if (norm != name) throw ArchiveException("Nome de entrada não normalizado: $name")
            if (!names.add(name)) throw ArchiveException("Entrada duplicada: $name")
        }
        AtomicFiles.writeWith(out) { os ->
            val zos = ZipOutputStream(os)
            for ((name, path) in entries.sortedBy { it.first }) {
                zos.putNextEntry(ZipEntry(name))
                Files.copy(path, zos)
                zos.closeEntry()
            }
            zos.finish() // não fecha: o AtomicFiles ainda precisa sincronizar o arquivo
        }
    }

    /** Compacta o conteúdo de [sourceDir]; [include] decide, por nome relativo, o que entra. */
    fun create(sourceDir: Path, out: Path, include: (String) -> Boolean = { true }) {
        val entries = studio.core.storage.walkFiles(sourceDir)
            .map { studio.core.storage.relativeName(sourceDir, it) to it }
            .filter { include(it.first) }
        createFromEntries(out, entries)
    }
}
