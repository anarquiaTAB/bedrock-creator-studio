package studio.core.addon

import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity
import studio.core.json.*
import studio.core.manifest.ManifestReader
import studio.core.manifest.PackDependency
import studio.core.project.Project
import studio.core.storage.StorageException

class WriteResult(val written: List<String>, val diagnostics: List<Diagnostic>) {
    val ok: Boolean get() = diagnostics.none { it.severity == Severity.ERROR }
}

/**
 * Grava definições validadas nos pacotes (packs/BP e packs/RP) de um [Project].
 * Tudo ou nada: se houver erro de validação, arquivo existente (com overwrite = false) ou falha ao
 * mesclar atlas/idiomas, NADA é gravado. Os arquivos gerados são JSON comum e podem ser editados à mão.
 */
class AddonWriter(
    private val project: Project,
    private val bp: String = "BP",
    private val rp: String = "RP",
    private val versions: FormatVersions = FormatVersions(),
    private val overwrite: Boolean = false,
    private val lang: String = "en_US",
) {
    private class Plan {
        val diagnostics = mutableListOf<Diagnostic>()
        val files = LinkedHashMap<String, String>()
        val merges = LinkedHashMap<String, (String?) -> String>()
    }

    private fun bpPath(rel: String) = "packs/$bp/$rel"
    private fun rpPath(rel: String) = "packs/$rp/$rel"
    private fun json(o: JsonObj) = Json.write(o) + "\n"

    private fun rpName(): String {
        val p = rpPath("manifest.json")
        if (!project.exists(p)) return "resource_pack"
        return try {
            ManifestReader.parse(Json.parseObject(project.readText(p))).name ?: "resource_pack"
        } catch (e: Exception) {
            "resource_pack"
        }
    }

    private fun declaredModules(): Set<String>? {
        val p = bpPath("manifest.json")
        if (!project.exists(p)) return null
        return try {
            ManifestReader.parse(Json.parseObject(project.readText(p))).dependencies
                .filterIsInstance<PackDependency.OnModule>().map { it.moduleName }.toSet()
        } catch (e: Exception) {
            null
        }
    }

    private fun langMerge(plan: Plan, entries: Map<String, String>) {
        if (entries.isEmpty()) return
        val path = rpPath("texts/$lang.lang")
        val prev = plan.merges[path]
        plan.merges[path] = { existing -> LangFile.merge(prev?.invoke(existing) ?: existing, entries) }
    }

    private fun commit(plan: Plan): WriteResult {
        val diags = plan.diagnostics
        if (diags.any { it.severity == Severity.ERROR }) return WriteResult(emptyList(), diags)
        if (!overwrite) {
            for (rel in plan.files.keys) {
                if (project.exists(rel)) diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "FILE_EXISTS", "O arquivo '$rel' já existe; nada foi gravado.", rel))
            }
            if (diags.any { it.severity == Severity.ERROR }) return WriteResult(emptyList(), diags)
        }
        val merged = LinkedHashMap<String, String>()
        for ((rel, fn) in plan.merges) {
            try {
                merged[rel] = fn(if (project.exists(rel)) project.readText(rel) else null)
            } catch (e: JsonParseException) {
                diags.add(Diagnostic(Severity.ERROR, Category.SYNTAX, "MERGE_FAILED", "Não foi possível mesclar '$rel': ${e.message}", rel, e.line, e.column))
            } catch (e: IllegalArgumentException) {
                diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "MERGE_FAILED", "Não foi possível mesclar '$rel': ${e.message}", rel))
            } catch (e: StorageException) {
                diags.add(Diagnostic(Severity.ERROR, Category.STRUCTURE, "MERGE_FAILED", "Não foi possível ler '$rel': ${e.message}", rel))
            }
        }
        if (diags.any { it.severity == Severity.ERROR }) return WriteResult(emptyList(), diags)
        val written = mutableListOf<String>()
        for ((rel, text) in plan.files) {
            project.writeText(rel, text)
            written.add(rel)
        }
        for ((rel, text) in merged) {
            project.writeText(rel, text)
            written.add(rel)
        }
        diags.add(
            Diagnostic(
                Severity.INFO, Category.NEEDS_GAME_TEST, "GAME_TEST_REQUIRED",
                "Arquivos validados apenas pelo catálogo interno do estúdio (escrito sem acesso à documentação oficial). Teste no Minecraft da versão-alvo.",
            ),
        )
        return WriteResult(written, diags)
    }

    fun writeEntity(
        def: EntityDef,
        client: ClientEntityDef? = null,
        renderController: RenderControllerDef? = null,
        displayName: String? = null,
        spawnEggName: String? = null,
    ): WriteResult {
        val plan = Plan()
        val name = shortName(def.identifier)
        val bpRel = bpPath("entities/$name.json")
        plan.diagnostics += def.validate(bpRel)
        plan.files[bpRel] = json(def.toJson(versions))
        if (client != null) {
            val rel = rpPath("entity/$name.entity.json")
            if (client.identifier != def.identifier) plan.diagnostics.add(err("CLIENT_IDENTIFIER", "O identificador da definição de cliente difere do da entidade.", rel))
            plan.diagnostics += client.validate(rel)
            if (renderController != null && renderController.name !in client.renderControllers) {
                plan.diagnostics.add(warn("RENDER_CONTROLLER_UNUSED", "A definição de cliente não usa o render controller '${renderController.name}'.", rel))
            }
            plan.files[rel] = json(client.toJson(versions))
        }
        if (renderController != null) {
            val rel = rpPath("render_controllers/$name.render_controllers.json")
            plan.diagnostics += renderController.validate(rel)
            plan.files[rel] = json(renderController.toJson(versions))
        }
        val names = LinkedHashMap<String, String>()
        displayName?.let { names[LangFile.entityKey(def.identifier)] = it }
        spawnEggName?.let { names[LangFile.spawnEggKey(def.identifier)] = it }
        try { langMerge(plan, names) } catch (e: IllegalArgumentException) { plan.diagnostics.add(err("LANG_INVALID", e.message ?: "Texto inválido")) }
        return commit(plan)
    }

    fun writeBlock(def: BlockDef, displayName: String? = null, terrainTexture: Pair<String, String>? = null): WriteResult {
        val plan = Plan()
        val rel = bpPath("blocks/${shortName(def.identifier)}.json")
        plan.diagnostics += def.validate(rel)
        plan.files[rel] = json(def.toJson(versions))
        terrainTexture?.let { (short, path) ->
            val atlas = rpPath("textures/terrain_texture.json")
            val pack = rpName()
            plan.merges[atlas] = { existing -> Atlas.mergeTerrain(existing, pack, short, path) }
        }
        displayName?.let { try { langMerge(plan, mapOf(LangFile.blockKey(def.identifier) to it)) } catch (e: IllegalArgumentException) { plan.diagnostics.add(err("LANG_INVALID", e.message ?: "Texto inválido")) } }
        return commit(plan)
    }

    fun writeItem(def: ItemDef, displayName: String? = null, iconTexture: Pair<String, String>? = null): WriteResult {
        val plan = Plan()
        val rel = bpPath("items/${shortName(def.identifier)}.json")
        plan.diagnostics += def.validate(rel)
        plan.files[rel] = json(def.toJson(versions))
        iconTexture?.let { (short, path) ->
            val atlas = rpPath("textures/item_texture.json")
            val pack = rpName()
            plan.merges[atlas] = { existing -> Atlas.mergeItem(existing, pack, short, path) }
        }
        displayName?.let { try { langMerge(plan, mapOf(LangFile.itemKey(def.identifier) to it)) } catch (e: IllegalArgumentException) { plan.diagnostics.add(err("LANG_INVALID", e.message ?: "Texto inválido")) } }
        return commit(plan)
    }

    fun writeRecipe(r: RecipeDef): WriteResult {
        val plan = Plan()
        val rel = bpPath("recipes/${shortName(r.identifier)}.json")
        plan.diagnostics += r.validate(rel)
        plan.files[rel] = json(r.toJson(versions))
        return commit(plan)
    }

    /** [path] é relativo a loot_tables/, sem extensão (ex.: "entities/ruby_golem"). */
    fun writeLootTable(path: String, table: LootTable): WriteResult {
        val plan = Plan()
        val rel = bpPath("loot_tables/$path.json")
        if (!Regex("^[a-z0-9_]+(/[a-z0-9_]+)*\$").matches(path)) plan.diagnostics.add(err("LOOT_PATH", "Caminho de loot table inválido '$path' (use a-z, 0-9, _ e /).", rel))
        plan.diagnostics += table.validate(rel)
        plan.files[rel] = json(table.toJson())
        return commit(plan)
    }

    fun writeSpawnRules(def: SpawnRulesDef): WriteResult {
        val plan = Plan()
        val rel = bpPath("spawn_rules/${shortName(def.identifier)}.json")
        plan.diagnostics += def.validate(rel)
        plan.files[rel] = json(def.toJson(versions))
        return commit(plan)
    }

    /** [name] sem extensão; pode ter subpastas (ex.: "util/spawn"). */
    fun writeFunction(name: String, text: String): WriteResult {
        val plan = Plan()
        val rel = bpPath("functions/$name.mcfunction")
        if (!Regex("^[a-z0-9_]+(/[a-z0-9_]+)*\$").matches(name)) plan.diagnostics.add(err("FUNCTION_NAME", "Nome de função inválido '$name'.", rel))
        plan.diagnostics += FunctionLint.check(text, rel)
        plan.files[rel] = text
        return commit(plan)
    }

    fun writeScript(entry: String = "scripts/main.js", source: String = Scaffold.mainJs()): WriteResult {
        val plan = Plan()
        val rel = bpPath(entry)
        if (!entry.endsWith(".js") || entry.contains("..") || entry.startsWith("/")) plan.diagnostics.add(err("SCRIPT_PATH", "Caminho de script inválido '$entry'.", rel))
        plan.diagnostics += ScriptLint.check(source, rel, declaredModules())
        plan.files[rel] = source
        return commit(plan)
    }
}

/** Edição de componentes direto no arquivo real (o editor visual e o editor técnico compartilham o mesmo JSON). */
object AddonEdit {
    private val ROOTS = listOf("minecraft:entity" to ComponentKind.ENTITY, "minecraft:block" to ComponentKind.BLOCK, "minecraft:item" to ComponentKind.ITEM)

    private fun rootOf(doc: JsonObj, rel: String): Pair<String, ComponentKind> =
        ROOTS.firstOrNull { doc.obj(it.first) != null } ?: throw StorageException("'$rel' não contém minecraft:entity, minecraft:block nem minecraft:item")

    private fun parse(project: Project, rel: String): JsonObj = try {
        Json.parseObject(project.readText(rel))
    } catch (e: JsonParseException) {
        throw StorageException("'$rel' não é um JSON válido: ${e.message}", e)
    }

    fun listComponents(project: Project, rel: String): Map<String, JsonValue> {
        val doc = parse(project, rel)
        val (key, _) = rootOf(doc, rel)
        return doc.obj(key)!!.obj("components")?.fields ?: emptyMap()
    }

    /** Valida e grava o componente; se houver erro, o arquivo não é alterado. Campos desconhecidos são mantidos. */
    fun setComponent(project: Project, rel: String, name: String, value: JsonValue): List<Diagnostic> {
        val doc = parse(project, rel)
        val (key, kind) = rootOf(doc, rel)
        val diags = Catalog.validate(kind, name, value, rel)
        if (diags.any { it.severity == Severity.ERROR }) return diags
        project.updateJson(rel) { root ->
            val body = root.obj(key)!!
            val comps = body.obj("components") ?: JsonObj.empty()
            root.with(key, body.with("components", comps.with(name, value)))
        }
        return diags
    }

    fun removeComponent(project: Project, rel: String, name: String): Boolean {
        val doc = parse(project, rel)
        val (key, _) = rootOf(doc, rel)
        if (doc.obj(key)!!.obj("components")?.get(name) == null) return false
        project.updateJson(rel) { root ->
            val body = root.obj(key)!!
            root.with(key, body.with("components", body.obj("components")!!.without(name)))
        }
        return true
    }
}
