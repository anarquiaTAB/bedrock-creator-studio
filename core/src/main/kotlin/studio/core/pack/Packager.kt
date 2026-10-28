package studio.core.pack

import studio.core.archive.ArchiveException
import studio.core.archive.SafeZip
import studio.core.diagnostics.*
import studio.core.json.Json
import studio.core.json.JsonObj
import studio.core.json.JsonParseException
import studio.core.manifest.ManifestReader
import studio.core.manifest.PackDependency
import studio.core.manifest.ParsedManifest
import studio.core.storage.AtomicFiles
import studio.core.storage.relativeName
import studio.core.storage.walkFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class InspectedPack(val dir: String, val manifest: ParsedManifest)

/** [kind]: "mcpack" (manifest na raiz), "mcaddon" (uma pasta por pacote) ou "unknown". */
class ArchiveReport(val kind: String, val packs: List<InspectedPack>, val diagnostics: List<Diagnostic>) {
    val hasErrors: Boolean get() = diagnostics.any { it.severity == Severity.ERROR }
}

/** [output] é null quando a exportação foi recusada ou a verificação final falhou. */
class PackResult(val output: Path?, val report: DiagnosticReport, val verification: ArchiveReport?)

object Packager {
    fun exportMcpack(packDir: Path, out: Path, force: Boolean = false): PackResult =
        export(listOf(packDir.fileName.toString() to packDir), out, force, single = true)

    fun exportMcaddon(packs: List<Pair<String, Path>>, out: Path, force: Boolean = false): PackResult =
        export(packs, out, force, single = false)

    private fun fail(code: String, msg: String) =
        PackResult(null, DiagnosticReport(listOf(Diagnostic(Severity.ERROR, Category.STRUCTURE, code, msg))), null)

    private fun export(packs: List<Pair<String, Path>>, out: Path, force: Boolean, single: Boolean): PackResult {
        if (packs.isEmpty()) return fail("NO_PACKS", "Nenhum pacote para exportar.")
        val names = packs.map { it.first }
        if (names.any { it.isBlank() || it.contains('/') || it.contains('\\') || it == "." || it == ".." }) {
            return fail("PACK_NAME_INVALID", "Nome de pasta de pacote inválido.")
        }
        if (names.toSet().size != names.size) return fail("PACK_NAME_DUPLICATE", "Há pacotes com o mesmo nome de pasta.")
        for ((name, dir) in packs) {
            if (!Files.isRegularFile(dir.resolve("manifest.json"))) {
                return fail("MANIFEST_MISSING", "O pacote '$name' não tem manifest.json; não é possível exportar.")
            }
        }

        val report = ProjectChecker.check(packs)
        if (report.hasErrors && !force) return PackResult(null, report, null)

        val entries = mutableListOf<Pair<String, Path>>()
        for ((name, dir) in packs) {
            for (p in walkFiles(dir)) {
                val rel = relativeName(dir, p)
                if (rel.startsWith(".studio/") || rel.endsWith(AtomicFiles.TMP_SUFFIX)) continue
                entries.add((if (single) rel else "$name/$rel") to p)
            }
        }
        try {
            SafeZip.createFromEntries(out, entries)
        } catch (e: ArchiveException) {
            return fail("PACKAGE_WRITE_FAILED", e.message ?: "Falha ao criar o pacote.")
        }

        val check = inspect(out)
        if (check.hasErrors) {
            Files.deleteIfExists(out)
            return PackResult(null, report + DiagnosticReport(check.diagnostics + Diagnostic(Severity.ERROR, Category.STRUCTURE, "PACKAGE_VERIFY_FAILED", "A verificação final do arquivo exportado falhou; o arquivo foi removido.")), check)
        }
        return PackResult(out, report, check)
    }

    /** Abre o arquivo exportado e confere sua estrutura interna (não basta existir). */
    fun inspect(archive: Path): ArchiveReport {
        val diags = mutableListOf<Diagnostic>()
        val packs = mutableListOf<InspectedPack>()
        var kind = "unknown"
        try {
            ZipFile(archive.toFile()).use { zf ->
                val entries = LinkedHashMap<String, java.util.zip.ZipEntry>()
                for (e in zf.entries().asSequence()) {
                    val n = try {
                        SafeZip.normalizeEntryName(e.name)
                    } catch (ex: ArchiveException) {
                        diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "ARCHIVE_UNSAFE", ex.message ?: "Entrada insegura"))
                        continue
                    }
                    if (n.isEmpty() || e.isDirectory) continue
                    entries[n] = e
                }
                val hasRoot = "manifest.json" in entries
                val dirs = entries.keys
                    .filter { it.endsWith("/manifest.json") && it.count { c -> c == '/' } == 1 }
                    .map { it.substringBefore('/') }
                val targets = when {
                    hasRoot -> { kind = "mcpack"; listOf("") }
                    dirs.isNotEmpty() -> { kind = "mcaddon"; dirs }
                    else -> emptyList()
                }
                if (targets.isEmpty()) {
                    val nested = entries.keys.any { it.endsWith(".mcpack", ignoreCase = true) }
                    if (nested) {
                        kind = "mcaddon"
                        diags.add(Diagnostic(Severity.WARNING, Category.STRUCTURE, "NESTED_MCPACK", "O arquivo contém .mcpack aninhados; a inspeção não é recursiva."))
                    } else {
                        diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "NO_MANIFEST", "Nenhum manifest.json válido encontrado na raiz nem em pastas de primeiro nível."))
                    }
                }
                for (dir in targets) {
                    val path = if (dir.isEmpty()) "manifest.json" else "$dir/manifest.json"
                    val text = zf.getInputStream(entries.getValue(path)).use { String(it.readBytes(), Charsets.UTF_8) }
                    try {
                        val obj = Json.parse(text) as? JsonObj
                        if (obj == null) {
                            diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "MANIFEST_NOT_OBJECT", "A raiz do manifest deve ser um objeto.", path))
                        } else {
                            val m = ManifestReader.parse(obj)
                            if (m.headerUuid == null || m.moduleUuids.isEmpty()) {
                                diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "MANIFEST_INCOMPLETE", "Manifest sem header.uuid ou sem módulos.", path))
                            }
                            packs.add(InspectedPack(dir, m))
                        }
                    } catch (e: JsonParseException) {
                        diags.add(Diagnostic(Severity.ERROR, Category.SYNTAX, "JSON_SYNTAX", "JSON inválido: ${e.detail}", path, e.line, e.column))
                    }
                }
            }
        } catch (e: java.io.IOException) {
            diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "ARCHIVE_UNREADABLE", "Não foi possível ler o arquivo: ${e.message}"))
        }

        val owner = HashMap<String, String>()
        for (p in packs) {
            val u = p.manifest.headerUuid ?: continue
            val prev = owner.putIfAbsent(u, p.dir)
            if (prev != null) diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "UUID_DUPLICATE_ACROSS_PACKS", "UUID $u repetido em '$prev' e '${p.dir}'."))
        }
        val headers = packs.mapNotNull { it.manifest.headerUuid }.toSet()
        for (p in packs) for (d in p.manifest.dependencies) {
            if (d is PackDependency.OnPack && d.uuid !in headers) {
                diags.add(Diagnostic(Severity.WARNING, Category.STRUCTURE, "DEP_UNRESOLVED", "Dependência ${d.uuid} não está neste arquivo; precisa estar instalada no jogo.", p.dir))
            }
        }
        return ArchiveReport(kind, packs, diags)
    }
}
