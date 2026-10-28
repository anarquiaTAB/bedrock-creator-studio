package studio.core.project

import studio.core.archive.ArchiveException
import studio.core.archive.SafeZip
import studio.core.manifest.Uuids
import studio.core.storage.AtomicFiles
import studio.core.storage.StorageException
import studio.core.storage.io
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** O que foi encontrado/reparado ao abrir um projeto (ex.: após o app ser encerrado inesperadamente). */
data class Recovery(val removedTempFiles: Int, val pendingDrafts: List<String>)

class OpenResult(val project: Project, val recovery: Recovery)

class ProjectStore(private val base: Path) {
    private val idRegex = Regex("^[A-Za-z0-9-]{1,64}$")

    private fun dirFor(id: String): Path {
        if (!idRegex.matches(id)) throw StorageException("Identificador de projeto inválido")
        return base.resolve(id)
    }

    fun create(name: String, targetMinecraft: String? = null): Project {
        if (name.isBlank()) throw StorageException("O nome do projeto não pode ficar vazio")
        val id = Uuids.random()
        val root = dirFor(id)
        io("Falha ao criar o projeto") { Files.createDirectories(root.resolve(Project.PACKS_DIR)) }
        val now = System.currentTimeMillis()
        val project = Project(root, ProjectMeta(id, name.trim(), targetMinecraft, now, now))
        project.saveMeta(project.meta)
        return project
    }

    /** Projetos legíveis, mais recentes primeiro. Pastas sem project.json válido são ignoradas. */
    fun list(): List<ProjectMeta> {
        if (!Files.isDirectory(base)) return emptyList()
        val dirs = io("Falha ao listar projetos") {
            Files.list(base).use { s -> s.iterator().asSequence().filter { Files.isDirectory(it) }.toList() }
        }
        return dirs.mapNotNull {
            try {
                MetaJson.read(it).first
            } catch (e: StorageException) {
                null
            }
        }.sortedByDescending { it.modifiedAt }
    }

    fun open(id: String): OpenResult {
        val root = dirFor(id)
        if (!Files.isDirectory(root)) throw StorageException("Projeto não encontrado")
        val meta = MetaJson.read(root).first
        val removed = AtomicFiles.cleanupStale(root)
        val project = Project(root, meta)
        return OpenResult(project, Recovery(removed, project.pendingDrafts()))
    }

    fun duplicate(id: String, newName: String): Project {
        val src = open(id).project
        val copy = create(newName, src.meta.targetMinecraft)
        io("Falha ao duplicar o projeto") {
            for (rel in src.listFiles()) {
                val to = copy.root.resolve(rel)
                Files.createDirectories(to.parent)
                Files.copy(src.root.resolve(rel), to, StandardCopyOption.REPLACE_EXISTING)
            }
            // Pastas de pacote vazias também são mantidas
            for ((name, _) in src.packDirs()) Files.createDirectories(copy.root.resolve(Project.PACKS_DIR).resolve(name))
        }
        return open(copy.meta.id).project
    }

    fun delete(id: String) {
        val root = dirFor(id)
        if (!Files.isDirectory(root)) throw StorageException("Projeto não encontrado")
        io("Falha ao excluir o projeto") { root.toFile().deleteRecursively() }
    }

    /** Backup editável em .zip (inclui project.json; exclui a área interna de rascunhos). */
    fun exportBackup(id: String, out: Path) {
        val project = open(id).project
        SafeZip.create(project.root, out) { name ->
            !name.startsWith(Project.INTERNAL_DIR + "/") && !name.endsWith(AtomicFiles.TMP_SUFFIX)
        }
    }

    /**
     * Importa .zip de backup, .mcpack, .mcaddon (inclusive com .mcpack aninhados).
     * Nunca altera o arquivo original. A extração passa por [SafeZip] (proteção contra zip-slip).
     */
    fun importArchive(archive: Path, name: String? = null): Project {
        io("Falha ao preparar a importação") { Files.createDirectories(base) }
        val staging = io("Falha ao criar pasta temporária") { Files.createTempDirectory(base, ".import-") }
        try {
            SafeZip.extract(archive, staging)
            val baseName = name?.takeIf { it.isNotBlank() } ?: archive.fileName.toString().substringBeforeLast('.')
            return if (Files.isRegularFile(staging.resolve(Project.META_FILE))) {
                restoreBackup(staging, name)
            } else {
                buildFromPacks(staging, baseName)
            }
        } catch (t: Throwable) {
            staging.toFile().deleteRecursively()
            throw t
        }
    }

    private fun restoreBackup(staging: Path, name: String?): Project {
        val (old, oldJson) = MetaJson.read(staging)
        val newId = Uuids.random()
        val target = dirFor(newId)
        io("Falha ao restaurar o backup") { Files.move(staging, target) }
        val now = System.currentTimeMillis()
        val meta = old.copy(id = newId, name = name?.takeIf { it.isNotBlank() } ?: old.name, modifiedAt = now)
        val project = Project(target, meta)
        AtomicFiles.writeText(target.resolve(Project.META_FILE), studio.core.json.Json.write(MetaJson.toJson(meta, oldJson)) + "\n")
        return project
    }

    private fun sanitize(n: String): String {
        val s = n.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return if (s.isEmpty()) "pack" else s
    }

    private fun buildFromPacks(staging: Path, projectName: String): Project {
        val id = Uuids.random()
        val root = dirFor(id)
        val packsDir = root.resolve(Project.PACKS_DIR)
        io("Falha ao criar o projeto") { Files.createDirectories(packsDir) }
        var found = 0
        try {
            if (Files.isRegularFile(staging.resolve("manifest.json"))) {
                io("Falha ao mover o pacote") { Files.move(staging, packsDir.resolve(sanitize(projectName))) }
                found = 1
            } else {
                val children = io("Falha ao ler o conteúdo") {
                    Files.list(staging).use { s -> s.iterator().asSequence().toList() }
                }
                for (c in children.sortedBy { it.fileName.toString() }) {
                    val n = c.fileName.toString()
                    if (Files.isDirectory(c) && Files.isRegularFile(c.resolve("manifest.json"))) {
                        io("Falha ao mover o pacote") { Files.move(c, packsDir.resolve(sanitize(n))) }
                        found++
                    } else if (Files.isRegularFile(c) && n.endsWith(".mcpack", ignoreCase = true)) {
                        SafeZip.extract(c, packsDir.resolve(sanitize(n.substringBeforeLast('.'))))
                        found++
                    }
                }
            }
            if (found == 0) throw ArchiveException("O arquivo não contém nenhum pacote reconhecível (sem manifest.json)")
        } catch (t: Throwable) {
            root.toFile().deleteRecursively()
            throw t
        }
        staging.toFile().deleteRecursively()
        val now = System.currentTimeMillis()
        val project = Project(root, ProjectMeta(id, projectName.trim(), null, now, now))
        project.saveMeta(project.meta)
        return project
    }
}
