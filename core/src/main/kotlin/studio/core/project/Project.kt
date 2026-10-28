package studio.core.project

import studio.core.diagnostics.DiagnosticReport
import studio.core.diagnostics.ProjectChecker
import studio.core.json.*
import studio.core.storage.AtomicFiles
import studio.core.storage.StorageException
import studio.core.storage.io
import studio.core.storage.relativeName
import studio.core.storage.walkFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class ProjectMeta(
    val id: String,
    val name: String,
    val targetMinecraft: String?,
    val createdAt: Long,
    val modifiedAt: Long,
)

internal object MetaJson {
    fun toJson(m: ProjectMeta, base: JsonObj?): JsonObj {
        var o = base ?: JsonObj.empty()
        o = o.with("schema", JsonNum("1"))
            .with("id", JsonStr(m.id))
            .with("name", JsonStr(m.name))
            .with("createdAt", JsonNum(m.createdAt.toString()))
            .with("modifiedAt", JsonNum(m.modifiedAt.toString()))
        o = if (m.targetMinecraft != null) o.with("targetMinecraft", JsonStr(m.targetMinecraft)) else o.without("targetMinecraft")
        return o
    }

    fun fromJson(o: JsonObj): ProjectMeta {
        val id = o.str("id") ?: throw StorageException("project.json sem \"id\"")
        val name = o.str("name") ?: throw StorageException("project.json sem \"name\"")
        fun long(k: String) = (o[k] as? JsonNum)?.raw?.toLongOrNull() ?: 0L
        return ProjectMeta(id, name, o.str("targetMinecraft"), long("createdAt"), long("modifiedAt"))
    }

    fun read(root: Path): Pair<ProjectMeta, JsonObj> {
        val text = io("Falha ao ler project.json") { String(Files.readAllBytes(root.resolve(Project.META_FILE)), Charsets.UTF_8) }
        val obj = try {
            Json.parseObject(text)
        } catch (e: JsonParseException) {
            throw StorageException("project.json inválido: ${e.message}", e)
        }
        return fromJson(obj) to obj
    }
}

/**
 * Um projeto em disco. Todo o conteúdo é arquivo real sob [root]; os editores visuais e o editor
 * técnico usam esta mesma API, então não existe uma segunda cópia dos dados.
 */
class Project internal constructor(val root: Path, initial: ProjectMeta) {
    var meta: ProjectMeta = initial
        private set

    companion object {
        const val META_FILE = "project.json"
        const val INTERNAL_DIR = ".studio"
        const val PACKS_DIR = "packs"
    }

    private fun segments(rel: String): List<String> {
        val segs = rel.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.isEmpty() || segs.any { it == ".." } || rel.contains('\u0000')) {
            throw StorageException("Caminho inválido: '$rel'")
        }
        return segs
    }

    /** Resolve um caminho relativo ao projeto, recusando '..', caminhos absolutos e áreas reservadas. */
    fun resolve(rel: String): Path {
        if (rel.replace('\\', '/').startsWith("/")) throw StorageException("Caminho inválido: '$rel'")
        val segs = segments(rel)
        if (segs[0] == INTERNAL_DIR || (segs.size == 1 && segs[0] == META_FILE)) {
            throw StorageException("Caminho reservado pelo estúdio: '$rel'")
        }
        return resolveUnder(root, segs)
    }

    private fun resolveUnder(base: Path, segs: List<String>): Path {
        val p = base.resolve(segs.joinToString("/")).normalize()
        if (!p.startsWith(base.normalize())) throw StorageException("Caminho fora do projeto")
        return p
    }

    fun exists(rel: String): Boolean = Files.exists(resolve(rel))

    fun readBytes(rel: String): ByteArray {
        val p = resolve(rel)
        if (!Files.isRegularFile(p)) throw StorageException("Arquivo não encontrado: $rel")
        return io("Falha ao ler '$rel'") { Files.readAllBytes(p) }
    }

    fun readText(rel: String): String = String(readBytes(rel), Charsets.UTF_8)

    fun writeBytes(rel: String, bytes: ByteArray, overwrite: Boolean = true) {
        val p = resolve(rel)
        if (!overwrite && Files.exists(p)) throw StorageException("O arquivo já existe: $rel")
        AtomicFiles.writeBytes(p, bytes)
        touch()
    }

    fun writeText(rel: String, text: String, overwrite: Boolean = true) =
        writeBytes(rel, text.toByteArray(Charsets.UTF_8), overwrite)

    /** Cria um arquivo novo; falha se já existir (proteção contra sobrescrita acidental). */
    fun createFile(rel: String, bytes: ByteArray) = writeBytes(rel, bytes, overwrite = false)

    /**
     * Lê um JSON, aplica [transform] e regrava. Campos desconhecidos são mantidos.
     * Atenção: comentários do arquivo original não sobrevivem à reescrita.
     * Se o arquivo for inválido, nada é alterado.
     */
    fun updateJson(rel: String, transform: (JsonObj) -> JsonObj) {
        val parsed: JsonValue = try {
            Json.parse(readText(rel))
        } catch (e: JsonParseException) {
            throw StorageException("'$rel' não é um JSON válido: ${e.message}", e)
        }
        val current = parsed as? JsonObj ?: throw StorageException("'$rel' não é um objeto JSON")
        writeText(rel, Json.write(transform(current)) + "\n")
    }

    fun delete(rel: String) {
        val p = resolve(rel)
        if (!Files.exists(p)) throw StorageException("Não encontrado: $rel")
        io("Falha ao excluir '$rel'") {
            if (Files.isDirectory(p)) p.toFile().deleteRecursively() else Files.delete(p)
        }
        touch()
    }

    fun move(rel: String, newRel: String, overwrite: Boolean = false) {
        val from = resolve(rel)
        val to = resolve(newRel)
        if (!Files.exists(from)) throw StorageException("Não encontrado: $rel")
        if (Files.exists(to) && !overwrite) throw StorageException("O destino já existe: $newRel")
        io("Falha ao mover '$rel'") {
            Files.createDirectories(to.parent)
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
        touch()
    }

    /** Arquivos do projeto (nomes relativos), sem os arquivos internos do estúdio. */
    fun listFiles(): List<String> = walkFiles(root)
        .map { relativeName(root, it) }
        .filter { !it.startsWith("$INTERNAL_DIR/") && it != META_FILE }

    /** Pastas de pacote em packs/: pares (nome, diretório). */
    fun packDirs(): List<Pair<String, Path>> {
        val dir = root.resolve(PACKS_DIR)
        if (!Files.isDirectory(dir)) return emptyList()
        val children = io("Falha ao listar pacotes") {
            Files.list(dir).use { s -> s.iterator().asSequence().filter { Files.isDirectory(it) }.toList() }
        }
        return children.map { it.fileName.toString() to it }.sortedBy { it.first }
    }

    fun diagnose(): DiagnosticReport = ProjectChecker.check(packDirs())

    fun rename(newName: String) {
        if (newName.isBlank()) throw StorageException("O nome do projeto não pode ficar vazio")
        saveMeta(meta.copy(name = newName.trim(), modifiedAt = System.currentTimeMillis()))
    }

    private fun touch() = saveMeta(meta.copy(modifiedAt = System.currentTimeMillis()))

    internal fun saveMeta(newMeta: ProjectMeta) {
        val base = try {
            MetaJson.read(root).second
        } catch (e: StorageException) {
            null
        }
        AtomicFiles.writeText(root.resolve(META_FILE), Json.write(MetaJson.toJson(newMeta, base)) + "\n")
        meta = newMeta
    }

    // ---- Rascunhos (salvamento automático) ----

    private fun draftPath(rel: String): Path = resolveUnder(root.resolve(INTERNAL_DIR).resolve("drafts"), segments(rel))

    /** Guarda alterações ainda não confirmadas, sem tocar no arquivo real. */
    fun saveDraft(rel: String, content: String) {
        resolve(rel) // valida o caminho
        AtomicFiles.writeText(draftPath(rel), content)
    }

    fun pendingDrafts(): List<String> {
        val dir = root.resolve(INTERNAL_DIR).resolve("drafts")
        return walkFiles(dir).map { relativeName(dir, it) }
    }

    fun readDraft(rel: String): String? {
        val p = draftPath(rel)
        return if (Files.isRegularFile(p)) io("Falha ao ler rascunho") { String(Files.readAllBytes(p), Charsets.UTF_8) } else null
    }

    /** Aplica o rascunho ao arquivo real (de forma atômica) e o descarta. */
    fun commitDraft(rel: String) {
        val text = readDraft(rel) ?: throw StorageException("Não há rascunho para '$rel'")
        writeText(rel, text)
        discardDraft(rel)
    }

    fun discardDraft(rel: String) {
        val p = draftPath(rel)
        io("Falha ao descartar rascunho") { Files.deleteIfExists(p) }
    }
}
