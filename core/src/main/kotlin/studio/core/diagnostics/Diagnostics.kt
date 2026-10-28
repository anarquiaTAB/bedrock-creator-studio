package studio.core.diagnostics

import studio.core.addon.Catalog
import studio.core.addon.ComponentKind
import studio.core.image.Png
import studio.core.image.PngException
import studio.core.image.PngInfo
import studio.core.skin.SkinValidator
import studio.core.json.*
import studio.core.manifest.*
import studio.core.storage.AtomicFiles
import studio.core.storage.relativeName
import studio.core.storage.walkFiles
import java.nio.file.Files
import java.nio.file.Path

enum class Severity { ERROR, WARNING, INFO }

/** Separação exigida: sintaxe / estrutura / compatibilidade / precisa de teste no jogo. */
enum class Category { SYNTAX, STRUCTURE, COMPATIBILITY, NEEDS_GAME_TEST }

data class Diagnostic(
    val severity: Severity,
    val category: Category,
    val code: String,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

class DiagnosticReport(val items: List<Diagnostic>) {
    val errors: List<Diagnostic> get() = items.filter { it.severity == Severity.ERROR }
    val warnings: List<Diagnostic> get() = items.filter { it.severity == Severity.WARNING }
    val hasErrors: Boolean get() = items.any { it.severity == Severity.ERROR }
    operator fun plus(other: DiagnosticReport) = DiagnosticReport(items + other.items)
}

class PackCheckResult(val diagnostics: List<Diagnostic>, val manifest: ParsedManifest?)

private val IDENTIFIER = Regex("^[a-z0-9_.\\-]+:[a-z0-9_./\\-]+\$")
private val SEMVER = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.\\-]+)?(\\+[0-9A-Za-z.\\-]+)?\$")
private val KNOWN_MODULE_TYPES = setOf(
    "resources", "data", "client_data", "interface", "script", "skin_pack", "world_template", "plugin",
)

/** Verifica um pacote (pasta com manifest.json). [label] prefixa os nomes de arquivo nos relatórios. */
class PackChecker(private val packDir: Path, private val label: String) {
    private val out = mutableListOf<Diagnostic>()
    private val files = LinkedHashSet<String>()
    private val json = LinkedHashMap<String, JsonObj>()
    private val pngs = HashMap<String, PngInfo>()

    private fun shown(rel: String) = if (label.isEmpty()) rel else "$label/$rel"

    private fun add(
        sev: Severity, cat: Category, code: String, msg: String, rel: String?,
        e: JsonParseException? = null,
    ) {
        out.add(Diagnostic(sev, cat, code, msg, rel?.let { shown(it) }, e?.line, e?.column))
    }

    fun check(): PackCheckResult {
        for (p in walkFiles(packDir)) {
            val rel = relativeName(packDir, p)
            if (rel.startsWith(".studio/") || rel.endsWith(AtomicFiles.TMP_SUFFIX)) continue
            files.add(rel)
        }

        var parsed: ParsedManifest? = null
        if ("manifest.json" !in files) {
            add(Severity.ERROR, Category.STRUCTURE, "MANIFEST_MISSING", "O pacote não tem manifest.json na raiz.", null)
        } else {
            val root = readJson("manifest.json")
            if (root is JsonObj) {
                checkManifest(root)
                parsed = ManifestReader.parse(root)
            } else if (root != null) {
                add(Severity.ERROR, Category.STRUCTURE, "MANIFEST_NOT_OBJECT", "A raiz do manifest.json deve ser um objeto.", "manifest.json")
            }
        }

        for (rel in files.filter { it.endsWith(".json") && it != "manifest.json" }) readJson(rel)

        for (rel in files.filter { it.endsWith(".png", ignoreCase = true) }) checkPng(rel)
        checkSkinPack(parsed)
        checkAtlas("textures/item_texture.json")
        checkAtlas("textures/terrain_texture.json")
        checkClientEntities()
        checkIdentifiers()
        out.addAll(ModelChecks.check(json, ::shown))
        return PackCheckResult(out.toList(), parsed)
    }

    private fun readJson(rel: String): JsonValue? {
        val text = try {
            String(Files.readAllBytes(packDir.resolve(rel)), Charsets.UTF_8)
        } catch (e: java.io.IOException) {
            add(Severity.ERROR, Category.STRUCTURE, "FILE_UNREADABLE", "Não foi possível ler o arquivo: ${e.message}", rel)
            return null
        }
        return try {
            val v = Json.parse(text)
            if (v is JsonObj) json[rel] = v
            v
        } catch (e: JsonParseException) {
            add(Severity.ERROR, Category.SYNTAX, "JSON_SYNTAX", "JSON inválido: ${e.detail}", rel, e)
            null
        }
    }

    private fun checkPng(rel: String) {
        try {
            val buf = ByteArray(Png.HEADER_SIZE)
            var n = 0
            Files.newInputStream(packDir.resolve(rel)).use { input ->
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            val info = Png.readInfo(buf.copyOf(n))
            pngs[rel] = info
            if (info.interlaced) {
                add(Severity.WARNING, Category.COMPATIBILITY, "IMAGE_INTERLACED", "PNG entrelaçado: o editor do estúdio não consegue abri-lo.", rel)
            }
        } catch (e: PngException) {
            add(Severity.ERROR, Category.STRUCTURE, "IMAGE_INVALID", "Imagem PNG inválida: ${e.message}", rel)
        } catch (e: java.io.IOException) {
            add(Severity.ERROR, Category.STRUCTURE, "FILE_UNREADABLE", "Não foi possível ler a imagem: ${e.message}", rel)
        }
    }

    private fun versionOk(v: JsonValue?): Boolean = when (v) {
        is JsonArr -> v.items.size == 3 && v.items.all { (it as? JsonNum)?.toIntOrNull()?.let { n -> n >= 0 } == true }
        is JsonStr -> SEMVER.matches(v.value)
        else -> false
    }

    private fun checkManifest(m: JsonObj) {
        val f = "manifest.json"
        val fv = m["format_version"]
        if (fv == null) {
            add(Severity.ERROR, Category.STRUCTURE, "MANIFEST_FORMAT_VERSION", "Falta format_version.", f)
        } else if (fv is JsonNum && fv.raw != "1" && fv.raw != "2") {
            add(Severity.WARNING, Category.COMPATIBILITY, "MANIFEST_FORMAT_UNKNOWN", "format_version ${fv.raw} não é 1 nem 2; verifique a documentação oficial.", f)
        }

        val uuids = mutableListOf<String>()
        val header = m.obj("header")
        if (header == null) {
            add(Severity.ERROR, Category.STRUCTURE, "MANIFEST_HEADER", "Falta o objeto \"header\".", f)
        } else {
            if (header.str("name").isNullOrBlank()) add(Severity.ERROR, Category.STRUCTURE, "MANIFEST_NAME", "header.name ausente ou vazio.", f)
            val hu = header.str("uuid")
            if (hu == null || !Uuids.isValid(hu)) add(Severity.ERROR, Category.STRUCTURE, "UUID_INVALID", "header.uuid ausente ou inválido.", f) else uuids.add(hu)
            if (!versionOk(header["version"])) add(Severity.ERROR, Category.STRUCTURE, "MANIFEST_VERSION", "header.version deve ser [maior, menor, patch].", f)
            val me = header["min_engine_version"]
            if (me != null && !versionOk(me)) add(Severity.ERROR, Category.STRUCTURE, "MANIFEST_MIN_ENGINE", "header.min_engine_version inválido.", f)
        }

        val modules = m.arr("modules")
        if (modules == null || modules.items.isEmpty()) {
            add(Severity.ERROR, Category.STRUCTURE, "MANIFEST_MODULES", "Falta a lista \"modules\" (ao menos um módulo).", f)
        } else {
            var hasScript = false
            modules.items.forEachIndexed { idx, mv ->
                val mo = mv as? JsonObj
                if (mo == null) {
                    add(Severity.ERROR, Category.STRUCTURE, "MODULE_NOT_OBJECT", "modules[$idx] deve ser um objeto.", f)
                    return@forEachIndexed
                }
                val type = mo.str("type")
                if (type == null) {
                    add(Severity.ERROR, Category.STRUCTURE, "MODULE_TYPE", "modules[$idx].type ausente.", f)
                } else if (type !in KNOWN_MODULE_TYPES) {
                    add(Severity.WARNING, Category.COMPATIBILITY, "MODULE_TYPE_UNKNOWN", "Tipo de módulo desconhecido para este validador: \"$type\".", f)
                }
                val mu = mo.str("uuid")
                if (mu == null || !Uuids.isValid(mu)) add(Severity.ERROR, Category.STRUCTURE, "UUID_INVALID", "modules[$idx].uuid ausente ou inválido.", f) else uuids.add(mu)
                if (!versionOk(mo["version"])) add(Severity.ERROR, Category.STRUCTURE, "MODULE_VERSION", "modules[$idx].version inválida.", f)
                if (type == "script") {
                    hasScript = true
                    if (mo.str("language") != "javascript") {
                        add(Severity.WARNING, Category.COMPATIBILITY, "SCRIPT_LANGUAGE", "Módulo de script com language diferente de \"javascript\".", f)
                    }
                    val entry = mo.str("entry")
                    if (entry == null) {
                        add(Severity.ERROR, Category.STRUCTURE, "SCRIPT_ENTRY", "Módulo de script sem \"entry\".", f)
                    } else if (entry !in files) {
                        add(Severity.ERROR, Category.STRUCTURE, "SCRIPT_ENTRY_MISSING", "Arquivo de entrada do script não existe: $entry", f)
                    }
                }
            }
            val dupes = uuids.groupBy { it }.filter { it.value.size > 1 }.keys
            for (d in dupes) add(Severity.ERROR, Category.STRUCTURE, "UUID_DUPLICATE", "UUID repetido dentro do manifesto: $d", f)

            val deps = m.arr("dependencies")?.items?.mapNotNull { it as? JsonObj } ?: emptyList()
            val usesServer = deps.any { it.str("module_name") == "@minecraft/server" }
            if (hasScript && !usesServer) {
                add(Severity.WARNING, Category.STRUCTURE, "SCRIPT_NO_SERVER_DEP", "Há módulo de script, mas nenhuma dependência de @minecraft/server.", f)
            }
        }

        val rawDeps = m["dependencies"]
        if (rawDeps != null && rawDeps !is JsonArr) {
            add(Severity.ERROR, Category.STRUCTURE, "DEPENDENCIES_TYPE", "\"dependencies\" deve ser uma lista.", f)
        }
        (rawDeps as? JsonArr)?.items?.forEachIndexed { idx, dv ->
            val d = dv as? JsonObj
            val byUuid = d?.str("uuid") != null
            val byModule = d?.str("module_name") != null
            if (d == null || (!byUuid && !byModule)) {
                add(Severity.ERROR, Category.STRUCTURE, "DEPENDENCY_INVALID", "dependencies[$idx] precisa de uuid ou module_name.", f)
            } else if (byUuid && (!Uuids.isValid(d.str("uuid")!!) || !versionOk(d["version"]))) {
                add(Severity.ERROR, Category.STRUCTURE, "DEPENDENCY_INVALID", "dependencies[$idx] com uuid ou version inválidos.", f)
            } else if (byModule) {
                val v = d.str("version")
                if (v == null) {
                    add(Severity.ERROR, Category.STRUCTURE, "DEPENDENCY_INVALID", "dependencies[$idx] (módulo) precisa de version em texto.", f)
                } else if (d.str("module_name")!!.startsWith("@minecraft/") && v.contains("beta", ignoreCase = true)) {
                    add(Severity.WARNING, Category.COMPATIBILITY, "DEPENDENCY_BETA", "${d.str("module_name")} $v é beta/experimental: pode exigir a opção experimental e quebrar entre versões do jogo.", f)
                }
            }
        }
    }

    private fun checkSkinPack(parsed: ParsedManifest?) {
        if (parsed == null || "skin_pack" !in parsed.moduleTypes) return
        val root = json["skins.json"]
        if (root == null) {
            if ("skins.json" !in files) add(Severity.ERROR, Category.STRUCTURE, "SKINS_MISSING", "Pacote de skins sem skins.json.", null)
            return
        }
        val skins = root.arr("skins")
        if (skins == null || skins.items.isEmpty()) {
            add(Severity.ERROR, Category.STRUCTURE, "SKINS_EMPTY", "skins.json sem a lista \"skins\".", "skins.json")
            return
        }
        for (s in skins.items.mapNotNull { it as? JsonObj }) {
            val tex = s.str("texture")
            if (tex == null) {
                add(Severity.ERROR, Category.STRUCTURE, "SKIN_TEXTURE", "Skin sem campo \"texture\".", "skins.json")
            } else if (tex !in files) {
                add(Severity.ERROR, Category.STRUCTURE, "SKIN_TEXTURE_MISSING", "Textura da skin não encontrada: $tex", "skins.json")
            } else {
                pngs[tex]?.let { info -> SkinValidator.checkSize(info.width, info.height, shown(tex))?.let { out.add(it) } }
            }
        }
    }

    private fun textureExists(path: String) = path in files || "$path.png" in files || "$path.tga" in files

    private fun texturePaths(v: JsonValue): List<String> = when (v) {
        is JsonStr -> listOf(v.value)
        is JsonArr -> v.items.flatMap { texturePaths(it) }
        is JsonObj -> (v["path"] as? JsonStr)?.let { listOf(it.value) } ?: emptyList()
        else -> emptyList()
    }

    private fun missingTexture(path: String, ctx: String, rel: String) = add(
        Severity.WARNING, Category.STRUCTURE, "REF_TEXTURE_MISSING",
        "Textura '$path' ($ctx) não encontrada no pacote. Se não for um recurso do jogo base, o arquivo está faltando.", rel,
    )

    private fun checkAtlas(rel: String) {
        val data = json[rel]?.obj("texture_data") ?: return
        for ((key, v) in data.fields) {
            val tex = (v as? JsonObj)?.get("textures") ?: continue
            for (p in texturePaths(tex)) if (!textureExists(p)) missingTexture(p, "entrada '$key'", rel)
        }
    }

    private fun definedGeometries(): Set<String> {
        val ids = HashSet<String>()
        for ((rel, root) in json) {
            if (!rel.startsWith("models/")) continue
            root.arr("minecraft:geometry")?.items?.forEach { g ->
                (g as? JsonObj)?.obj("description")?.str("identifier")?.let { ids.add(it.substringBefore(':')) }
            }
            for (k in root.fields.keys) if (k.startsWith("geometry.")) ids.add(k.substringBefore(':'))
        }
        return ids
    }

    private fun checkClientEntities() {
        val geos = definedGeometries()
        for ((rel, root) in json) {
            if (!rel.startsWith("entity/")) continue
            val desc = root.obj("minecraft:client_entity")?.obj("description") ?: continue
            desc.obj("textures")?.fields?.forEach { (name, v) ->
                val p = (v as? JsonStr)?.value
                if (p != null && !textureExists(p)) missingTexture(p, "textura '$name'", rel)
            }
            desc.obj("geometry")?.fields?.forEach { (name, v) ->
                val g = (v as? JsonStr)?.value?.substringBefore(':')
                if (g != null && g !in geos) {
                    add(
                        Severity.WARNING, Category.STRUCTURE, "REF_GEOMETRY_MISSING",
                        "Geometria '$g' (\"$name\") não está definida neste pacote. Se não for uma geometria do jogo base, está faltando.", rel,
                    )
                }
            }
        }
    }

    private fun checkComponents(key: String, body: JsonObj, rel: String) {
        val kind = when (key) {
            "minecraft:entity" -> ComponentKind.ENTITY
            "minecraft:block" -> ComponentKind.BLOCK
            else -> ComponentKind.ITEM
        }
        val file = shown(rel)
        body.obj("components")?.let { out.addAll(Catalog.validateAll(kind, it.fields, file)) }
        if (kind == ComponentKind.ENTITY) {
            body.obj("component_groups")?.fields?.values?.forEach { g -> (g as? JsonObj)?.let { out.addAll(Catalog.validateAll(kind, it.fields, file)) } }
        }
        if (kind == ComponentKind.BLOCK) {
            body.arr("permutations")?.items?.forEach { p ->
                (p as? JsonObj)?.obj("components")?.let { out.addAll(Catalog.validateAll(kind, it.fields, file)) }
            }
        }
    }

    private fun checkIdentifiers() {
        val seen = HashMap<String, String>()
        for ((rel, root) in json) {
            if (!(rel.startsWith("entities/") || rel.startsWith("blocks/") || rel.startsWith("items/"))) continue
            for (key in listOf("minecraft:entity", "minecraft:block", "minecraft:item")) {
                val id = root.obj(key)?.obj("description")?.str("identifier") ?: continue
                checkComponents(key, root.obj(key)!!, rel)
                if (!IDENTIFIER.matches(id)) {
                    add(Severity.ERROR, Category.STRUCTURE, "IDENT_INVALID", "Identificador inválido '$id': use namespace:nome em minúsculas.", rel)
                    continue
                }
                if (id.startsWith("minecraft:")) {
                    add(Severity.WARNING, Category.COMPATIBILITY, "IDENT_VANILLA_NAMESPACE", "'$id' usa o namespace 'minecraft', reservado ao jogo base.", rel)
                }
                val prev = seen.put("$key|$id", rel)
                if (prev != null) {
                    add(Severity.ERROR, Category.STRUCTURE, "IDENT_DUPLICATE", "Identificador '$id' definido em '$prev' e em '$rel'.", rel)
                }
            }
        }
    }
}

object ProjectChecker {
    /** [packs]: pares (nome da pasta/rótulo, diretório do pacote). */
    fun check(packs: List<Pair<String, Path>>): DiagnosticReport {
        val all = mutableListOf<Diagnostic>()
        val parsed = mutableListOf<Pair<String, ParsedManifest>>()
        for ((label, dir) in packs) {
            val r = PackChecker(dir, label).check()
            all.addAll(r.diagnostics)
            r.manifest?.let { parsed.add(label to it) }
        }

        val owner = HashMap<String, String>()
        for ((label, m) in parsed) {
            for (u in listOfNotNull(m.headerUuid) + m.moduleUuids) {
                val prev = owner.putIfAbsent(u, label)
                if (prev != null && prev != label) {
                    all.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "UUID_DUPLICATE_ACROSS_PACKS", "UUID $u repetido nos pacotes '$prev' e '$label'.", "$label/manifest.json"))
                }
            }
        }

        val headers = parsed.mapNotNull { it.second.headerUuid }.toSet()
        for ((label, m) in parsed) {
            for (d in m.dependencies) {
                if (d is PackDependency.OnPack && d.uuid !in headers) {
                    all.add(Diagnostic(Severity.WARNING, Category.STRUCTURE, "DEP_UNRESOLVED", "Dependência ${d.uuid} não está entre os pacotes deste projeto; precisa estar instalada no jogo.", "$label/manifest.json"))
                }
            }
        }

        all.add(
            Diagnostic(
                Severity.INFO, Category.NEEDS_GAME_TEST, "GAME_TEST_REQUIRED",
                "Estas verificações são locais e estruturais. O funcionamento do conteúdo só é confirmado ao testá-lo no Minecraft.",
            ),
        )
        return DiagnosticReport(all)
    }
}
