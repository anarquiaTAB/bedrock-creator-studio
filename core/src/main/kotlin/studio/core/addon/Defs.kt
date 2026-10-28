package studio.core.addon

import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity
import studio.core.image.Colors
import studio.core.json.*

/**
 * Valores de "format_version" usados ao gerar arquivos. São pontos de partida escritos de memória:
 * confira e ajuste para a versão-alvo do Minecraft antes de distribuir.
 */
data class FormatVersions(
    val entity: String = "1.21.0",
    val block: String = "1.21.0",
    val item: String = "1.21.0",
    val recipe: String = "1.12.0",
    val clientEntity: String = "1.10.0",
    val renderController: String = "1.8.0",
    val spawnRules: String = "1.8.0",
)

internal val IDENT = Regex("^[a-z0-9_.\\-]+:[a-z0-9_./\\-]+\$")

internal fun err(code: String, msg: String, file: String? = null) =
    Diagnostic(Severity.ERROR, Category.STRUCTURE, code, msg, file)

internal fun warn(code: String, msg: String, file: String? = null) =
    Diagnostic(Severity.WARNING, Category.STRUCTURE, code, msg, file)

internal fun checkIdentifier(id: String, file: String?): List<Diagnostic> = when {
    !IDENT.matches(id) -> listOf(err("IDENT_INVALID", "Identificador inválido '$id': use namespace:nome em minúsculas (a-z, 0-9, _ . - /).", file))
    id.startsWith("minecraft:") -> listOf(warn("IDENT_VANILLA_NAMESPACE", "'$id' usa o namespace 'minecraft', reservado ao jogo base.", file))
    else -> emptyList()
}

/** Parte do identificador depois do namespace, usada como nome de arquivo. */
fun shortName(identifier: String): String = identifier.substringAfter(':').replace('/', '_')

private fun mapToJson(m: Map<String, JsonValue>): JsonObj {
    val out = LinkedHashMap<String, JsonValue>()
    out.putAll(m)
    return JsonObj(out)
}

data class EntityDef(
    val identifier: String,
    val isSpawnable: Boolean = true,
    val isSummonable: Boolean = true,
    val components: Map<String, JsonValue> = emptyMap(),
    val componentGroups: Map<String, Map<String, JsonValue>> = emptyMap(),
    val events: Map<String, JsonObj> = emptyMap(),
) {
    fun validate(file: String? = null): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += checkIdentifier(identifier, file)
        out += Catalog.validateAll(ComponentKind.ENTITY, components, file)
        for ((g, comps) in componentGroups) {
            if (g.isBlank()) out.add(err("GROUP_NAME", "Nome de grupo de componentes vazio.", file))
            out += Catalog.validateAll(ComponentKind.ENTITY, comps, file)
        }
        for ((name, ev) in events) {
            val refs = mutableListOf<String>()
            collectGroupRefs(ev, false, refs)
            for (r in refs) if (r !in componentGroups) out.add(err("EVENT_GROUP_MISSING", "O evento '$name' usa o grupo '$r', que não existe.", file))
        }
        return out
    }

    private fun collectGroupRefs(o: JsonObj, inAddRemove: Boolean, refs: MutableList<String>) {
        for ((k, v) in o.fields) {
            val inside = inAddRemove || k == "add" || k == "remove"
            when (v) {
                is JsonObj -> collectGroupRefs(v, inside, refs)
                is JsonArr -> {
                    if (k == "component_groups" && inAddRemove) v.items.forEach { (it as? JsonStr)?.let { s -> refs.add(s.value) } }
                    v.items.forEach { if (it is JsonObj) collectGroupRefs(it, inside, refs) }
                }
                else -> {}
            }
        }
    }

    fun toJson(v: FormatVersions = FormatVersions()): JsonObj {
        val desc = jsonObj("identifier" to js(identifier), "is_spawnable" to jb(isSpawnable), "is_summonable" to jb(isSummonable))
        val body = LinkedHashMap<String, JsonValue>()
        body["description"] = desc
        if (componentGroups.isNotEmpty()) body["component_groups"] = mapToJson(componentGroups.mapValues { mapToJson(it.value) })
        body["components"] = mapToJson(components)
        if (events.isNotEmpty()) body["events"] = mapToJson(events)
        return jsonObj("format_version" to js(v.entity), "minecraft:entity" to JsonObj(body))
    }
}

data class ClientEntityDef(
    val identifier: String,
    val textures: Map<String, String>,
    val geometry: Map<String, String>,
    val renderControllers: List<String>,
    val materials: Map<String, String> = mapOf("default" to "entity_alphatest"),
    val spawnEggColors: Pair<String, String>? = null,
) {
    fun validate(file: String? = null): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += checkIdentifier(identifier, file)
        if (textures.isEmpty()) out.add(err("CLIENT_TEXTURES", "Defina ao menos uma textura.", file))
        if (geometry.isEmpty()) out.add(err("CLIENT_GEOMETRY", "Defina ao menos uma geometria.", file))
        if (renderControllers.isEmpty()) out.add(err("CLIENT_RENDER", "Defina ao menos um render controller.", file))
        for ((k, p) in textures) if (p.endsWith(".png") || p.endsWith(".tga")) out.add(warn("TEXTURE_EXTENSION", "A textura '$k' não deve incluir a extensão do arquivo.", file))
        for (rc in renderControllers) if (!rc.startsWith("controller.render.")) out.add(err("RENDER_CONTROLLER_NAME", "Render controller '$rc' deve começar com 'controller.render.'.", file))
        spawnEggColors?.let { (a, b) ->
            for (c in listOf(a, b)) {
                try {
                    Colors.parseHex(c)
                } catch (e: IllegalArgumentException) {
                    out.add(err("SPAWN_EGG_COLOR", "Cor de ovo inválida: '$c'.", file))
                }
            }
        }
        return out
    }

    fun toJson(v: FormatVersions = FormatVersions()): JsonObj {
        val desc = LinkedHashMap<String, JsonValue>()
        desc["identifier"] = js(identifier)
        desc["materials"] = mapToJson(materials.mapValues { js(it.value) })
        desc["textures"] = mapToJson(textures.mapValues { js(it.value) })
        desc["geometry"] = mapToJson(geometry.mapValues { js(it.value) })
        desc["render_controllers"] = jstrings(renderControllers)
        spawnEggColors?.let { desc["spawn_egg"] = jsonObj("base_color" to js(it.first), "overlay_color" to js(it.second)) }
        return jsonObj("format_version" to js(v.clientEntity), "minecraft:client_entity" to jsonObj("description" to JsonObj(desc)))
    }
}

data class RenderControllerDef(
    val name: String,
    val geometryKey: String = "default",
    val materialKey: String = "default",
    val textureKey: String = "default",
) {
    fun validate(file: String? = null): List<Diagnostic> =
        if (name.startsWith("controller.render.") && name.length > "controller.render.".length) emptyList()
        else listOf(err("RENDER_CONTROLLER_NAME", "O nome deve começar com 'controller.render.'.", file))

    fun toJson(v: FormatVersions = FormatVersions()): JsonObj = jsonObj(
        "format_version" to js(v.renderController),
        "render_controllers" to jsonObj(
            name to jsonObj(
                "geometry" to js("Geometry.$geometryKey"),
                "materials" to ja(jsonObj("*" to js("Material.$materialKey"))),
                "textures" to ja(js("Texture.$textureKey")),
            ),
        ),
    )
}

private val CATEGORIES = setOf("construction", "nature", "equipment", "items", "none")

data class Permutation(val condition: String, val components: Map<String, JsonValue>)

data class BlockDef(
    val identifier: String,
    val category: String = "construction",
    val group: String? = null,
    val components: Map<String, JsonValue> = emptyMap(),
    val states: Map<String, List<JsonValue>> = emptyMap(),
    val permutations: List<Permutation> = emptyList(),
) {
    fun validate(file: String? = null): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += checkIdentifier(identifier, file)
        if (category !in CATEGORIES) out.add(err("MENU_CATEGORY", "Categoria de menu inválida '$category' (use ${CATEGORIES.joinToString()}).", file))
        out += Catalog.validateAll(ComponentKind.BLOCK, components, file)
        for ((s, values) in states) {
            if (!Regex("^[a-z0-9_]+:[a-z0-9_]+\$").matches(s)) out.add(err("STATE_NAME", "Nome de estado inválido '$s' (use namespace:nome).", file))
            if (values.isEmpty()) out.add(err("STATE_VALUES", "O estado '$s' não tem valores.", file))
            val kinds = values.map { it::class }.toSet()
            if (kinds.size > 1 || values.any { it !is JsonNum && it !is JsonBool && it !is JsonStr }) {
                out.add(err("STATE_TYPES", "Os valores do estado '$s' devem ser todos números, todos booleanos ou todos textos.", file))
            }
            if (values.toSet().size != values.size) out.add(err("STATE_DUPLICATE", "O estado '$s' tem valores repetidos.", file))
        }
        for ((i, perm) in permutations.withIndex()) {
            if (perm.condition.isBlank()) out.add(err("PERMUTATION_CONDITION", "A permutação ${i + 1} não tem condição.", file))
            out += Catalog.validateAll(ComponentKind.BLOCK, perm.components, file)
        }
        return out
    }

    fun toJson(v: FormatVersions = FormatVersions()): JsonObj {
        val cat = LinkedHashMap<String, JsonValue>()
        cat["category"] = js(category)
        if (group != null) cat["group"] = js(group)
        val desc = LinkedHashMap<String, JsonValue>()
        desc["identifier"] = js(identifier)
        desc["menu_category"] = JsonObj(cat)
        if (states.isNotEmpty()) desc["states"] = mapToJson(states.mapValues { JsonArr(it.value) })
        val body = LinkedHashMap<String, JsonValue>()
        body["description"] = JsonObj(desc)
        body["components"] = mapToJson(components)
        if (permutations.isNotEmpty()) {
            body["permutations"] = JsonArr(permutations.map { jsonObj("condition" to js(it.condition), "components" to mapToJson(it.components)) })
        }
        return jsonObj("format_version" to js(v.block), "minecraft:block" to JsonObj(body))
    }
}

data class ItemDef(
    val identifier: String,
    val category: String = "items",
    val group: String? = null,
    val components: Map<String, JsonValue> = emptyMap(),
) {
    fun validate(file: String? = null): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += checkIdentifier(identifier, file)
        if (category !in CATEGORIES) out.add(err("MENU_CATEGORY", "Categoria de menu inválida '$category' (use ${CATEGORIES.joinToString()}).", file))
        out += Catalog.validateAll(ComponentKind.ITEM, components, file)
        return out
    }

    fun toJson(v: FormatVersions = FormatVersions()): JsonObj {
        val cat = LinkedHashMap<String, JsonValue>()
        cat["category"] = js(category)
        if (group != null) cat["group"] = js(group)
        val body = jsonObj(
            "description" to jsonObj("identifier" to js(identifier), "menu_category" to JsonObj(cat)),
            "components" to mapToJson(components),
        )
        return jsonObj("format_version" to js(v.item), "minecraft:item" to body)
    }
}
