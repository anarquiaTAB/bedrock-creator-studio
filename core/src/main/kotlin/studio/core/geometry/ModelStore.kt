package studio.core.geometry

import studio.core.animation.*
import studio.core.json.*
import studio.core.project.Project
import studio.core.storage.StorageException
import studio.core.util.EditException

class GeometryRef(val file: String, val identifier: String, val legacy: Boolean, val editable: Boolean)
class AnimationRef(val file: String, val name: String, val editable: Boolean)
class ControllerRef(val file: String, val name: String, val editable: Boolean)

/**
 * Lê e grava geometrias, animações e controladores nos arquivos REAIS do pacote de recursos do projeto, e liga animações
 * às entidades de cliente. Só o item editado é regravado; o resto de cada arquivo (inclusive o que o estúdio não entende) é mantido.
 * Atenção: comentários do arquivo original não sobrevivem à regravação.
 */
class ModelStore(private val project: Project, private val rp: String = "RP") {
    private val base = "packs/$rp/"

    private fun files(dir: String): List<String> =
        project.listFiles().filter { it.startsWith("$base$dir/") && it.endsWith(".json") }.sorted()

    private fun parseObj(file: String): JsonObj = try {
        Json.parseObject(project.readText(file))
    } catch (e: JsonParseException) {
        throw StorageException("'$file' não é um JSON válido: ${e.message}", e)
    }

    private fun write(file: String, text: String) = project.writeText(file, text)

    // ---- geometrias ----

    fun geometryFiles(): List<String> = files("models")

    fun listGeometries(): List<GeometryRef> = geometryFiles().flatMap { f ->
        val gf = runCatching { GeometryFile.from(parseObj(f)) }.getOrNull() ?: return@flatMap emptyList()
        gf.entries(f).map { GeometryRef(f, it.identifier, gf.legacy, it.geometry != null) }
    }

    private val GeometryFile.legacy get() = LegacyGeometry.isLegacy(root)

    fun loadGeometry(file: String, identifier: String): Geometry {
        val en = GeometryFile.from(parseObj(file)).find(identifier, file) ?: throw StorageException("Geometria '$identifier' não encontrada em $file.")
        return en.geometry ?: throw StorageException("A geometria '$identifier' tem erros e não pode ser aberta no editor visual: ${en.diagnostics.joinToString("; ") { it.message }}")
    }

    /** Grava [g] no lugar da geometria [previousId] do arquivo. Arquivos no formato antigo devem ser convertidos antes ([convertLegacyGeometryFile]). */
    fun saveGeometry(file: String, g: Geometry, previousId: String = g.identifier) {
        val gf = GeometryFile.from(parseObj(file))
        write(file, gf.replace(g, previousId).toText())
    }

    /** Cria um arquivo novo com a geometria ou, se ele já existe, acrescenta a geometria a ele. */
    fun createGeometry(file: String, g: Geometry) {
        if (project.exists(file)) write(file, GeometryFile.from(parseObj(file)).add(g).toText())
        else project.createFile(file, GeometryFile.create(g).toText().toByteArray(Charsets.UTF_8))
    }

    /** Converte o arquivo antigo (1.8) para o formato novo e o regrava. Devolve o que mudou na conversão. */
    fun convertLegacyGeometryFile(file: String): List<studio.core.diagnostics.Diagnostic> {
        val (modern, diags) = GeometryFile.from(parseObj(file)).toModern(file = file)
        write(file, modern.toText())
        return diags
    }

    // ---- animações ----

    fun animationFiles(): List<String> = files("animations")

    fun listAnimations(): List<AnimationRef> = animationFiles().flatMap { f ->
        val af = runCatching { AnimationFile.from(parseObj(f)) }.getOrNull() ?: return@flatMap emptyList()
        af.entries(f).map { AnimationRef(f, it.name, it.animation != null) }
    }

    fun loadAnimation(file: String, name: String): Animation {
        val en = AnimationFile.from(parseObj(file)).find(name, file) ?: throw StorageException("Animação '$name' não encontrada em $file.")
        return en.animation ?: throw StorageException("A animação '$name' tem erros e não pode ser aberta no editor visual: ${en.diagnostics.joinToString("; ") { it.message }}")
    }

    fun saveAnimation(file: String, a: Animation, previousName: String = a.name) {
        write(file, AnimationFile.from(parseObj(file)).replace(a, previousName).toText())
    }

    fun createAnimation(file: String, a: Animation) {
        if (project.exists(file)) write(file, AnimationFile.from(parseObj(file)).add(a).toText())
        else project.createFile(file, AnimationFile.create(a).toText().toByteArray(Charsets.UTF_8))
    }

    // ---- controladores ----

    fun controllerFiles(): List<String> = files("animation_controllers")

    fun listControllers(): List<ControllerRef> = controllerFiles().flatMap { f ->
        val cf = runCatching { ControllerFile.from(parseObj(f)) }.getOrNull() ?: return@flatMap emptyList()
        cf.entries(f).map { ControllerRef(f, it.name, it.controller != null) }
    }

    fun loadController(file: String, name: String): Controller {
        val en = ControllerFile.from(parseObj(file)).find(name, file) ?: throw StorageException("Controlador '$name' não encontrado em $file.")
        return en.controller ?: throw StorageException("O controlador '$name' tem erros e não pode ser aberto no editor visual: ${en.diagnostics.joinToString("; ") { it.message }}")
    }

    fun saveController(file: String, c: Controller, previousName: String = c.name) {
        write(file, ControllerFile.from(parseObj(file)).replace(c, previousName).toText())
    }

    fun createController(file: String, c: Controller) {
        if (project.exists(file)) write(file, ControllerFile.from(parseObj(file)).add(c).toText())
        else project.createFile(file, ControllerFile.create(c).toText().toByteArray(Charsets.UTF_8))
    }

    // ---- ligação com entidades de cliente ----

    class EntityLinks(val geometry: Map<String, String>, val animations: Map<String, String>, val animate: List<String>)

    fun entityFiles(): List<String> = files("entity")

    fun entityLinks(entityFile: String): EntityLinks {
        val desc = parseObj(entityFile).obj("minecraft:client_entity")?.obj("description") ?: throw StorageException("'$entityFile' não é uma entidade de cliente.")
        fun strMap(k: String) = desc.obj(k)?.fields?.mapNotNull { (n, v) -> (v as? JsonStr)?.value?.let { n to it } }?.toMap(LinkedHashMap()) ?: LinkedHashMap()
        val animate = desc.obj("scripts")?.arr("animate")?.items?.flatMap {
            when (it) { is JsonStr -> listOf(it.value); is JsonObj -> it.fields.keys.toList(); else -> emptyList() }
        } ?: emptyList()
        return EntityLinks(strMap("geometry"), strMap("animations"), animate)
    }

    /**
     * Declara a animação/controlador [id] com o nome curto [shortName] na entidade e, se [animate], acrescenta
     * o nome em scripts.animate (com [condition] opcional, em Molang). Os demais campos da entidade ficam como estão.
     */
    fun attachAnimation(entityFile: String, shortName: String, id: String, animate: Boolean = true, condition: String? = null) {
        if (shortName.isBlank() || shortName.any { it.isWhitespace() }) throw EditException("Nome curto inválido.")
        condition?.let { c -> Molang.check(c)?.let { if (!it.unsupported) throw EditException("Condição inválida: ${it.message}") } }
        val links = entityLinks(entityFile)
        if (shortName in links.animations) throw EditException("A entidade já tem uma animação chamada '$shortName'.")
        project.updateJson(entityFile) { root ->
            val ce = root.obj("minecraft:client_entity") ?: throw EditException("Não é uma entidade de cliente.")
            var desc = ce.obj("description") ?: JsonObj.empty()
            desc = desc.with("animations", (desc.obj("animations") ?: JsonObj.empty()).with(shortName, JsonStr(id)))
            if (animate) {
                val scripts = desc.obj("scripts") ?: JsonObj.empty()
                val list = scripts.arr("animate")?.items ?: emptyList()
                val entry: JsonValue = if (condition == null) JsonStr(shortName) else jsonObj(shortName to JsonStr(condition))
                desc = desc.with("scripts", scripts.with("animate", JsonArr(list + entry)))
            }
            root.with("minecraft:client_entity", ce.with("description", desc))
        }
    }

    fun detachAnimation(entityFile: String, shortName: String) {
        if (shortName !in entityLinks(entityFile).animations) throw EditException("A entidade não tem a animação '$shortName'.")
        project.updateJson(entityFile) { root ->
            val ce = root.obj("minecraft:client_entity")!!
            var desc = ce.obj("description")!!
            val anims = desc.obj("animations")!!.without(shortName)
            desc = if (anims.fields.isEmpty()) desc.without("animations") else desc.with("animations", anims)
            desc.obj("scripts")?.arr("animate")?.let { arr ->
                val kept = arr.items.filter { when (it) { is JsonStr -> it.value != shortName; is JsonObj -> !it.fields.containsKey(shortName); else -> true } }
                desc = desc.with("scripts", desc.obj("scripts")!!.with("animate", JsonArr(kept)))
            }
            root.with("minecraft:client_entity", ce.with("description", desc))
        }
    }

    // ---- renomear osso em tudo ----

    /** Arquivos de animação (e nomes) usados por entidades que usam a geometria [geometryId]. */
    fun animationsUsingGeometry(geometryId: String): Set<String> {
        val ids = HashSet<String>()
        for (ef in entityFiles()) {
            val root = runCatching { parseObj(ef) }.getOrNull() ?: continue
            val desc = root.obj("minecraft:client_entity")?.obj("description") ?: continue
            val usesGeo = desc.obj("geometry")?.fields?.values?.any { (it as? JsonStr)?.value?.substringBefore(':') == geometryId } == true
            if (usesGeo) desc.obj("animations")?.fields?.values?.forEach { (it as? JsonStr)?.value?.let { id -> ids.add(id) } }
        }
        return ids
    }

    /**
     * Renomeia o osso na geometria e em todas as animações usadas pelas entidades que usam essa geometria. Tudo ou nada:
     * os textos novos são calculados antes e só então gravados. Devolve os arquivos alterados.
     */
    fun renameBoneEverywhere(geometryFile: String, geometryId: String, old: String, new: String): List<String> {
        val g = GeometryOps.renameBone(loadGeometry(geometryFile, geometryId), old, new)
        val pending = LinkedHashMap<String, String>()
        pending[geometryFile] = GeometryFile.from(parseObj(geometryFile)).replace(g).toText()
        val wanted = animationsUsingGeometry(geometryId)
        for (f in animationFiles()) {
            var af = AnimationFile.from(parseObj(f))
            var changed = false
            for (en in af.entries(f)) {
                val a = en.animation ?: continue
                if (en.name !in wanted) continue
                val key = a.bones.keys.firstOrNull { it == old } ?: continue
                af = af.replace(AnimationOps.renameBone(a, key, new))
                changed = true
            }
            if (changed) pending[f] = af.toText()
        }
        for ((f, t) in pending) write(f, t)
        return pending.keys.toList()
    }
}
