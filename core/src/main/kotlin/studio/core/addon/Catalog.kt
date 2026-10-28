package studio.core.addon

import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity
import studio.core.json.*

enum class ComponentKind { ENTITY, BLOCK, ITEM }

enum class ParamType { INT, NUMBER, BOOL, STRING, STRING_LIST, VEC3, LIST, OBJECT, ANY }

class ParamSpec(
    val name: String,
    val type: ParamType,
    val required: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val allowed: List<String>? = null,
)

/** [scalar]: o componente também pode ser escrito como valor simples. [openParams]: parâmetros extras não geram aviso. */
class ComponentSpec(
    val name: String,
    val params: List<ParamSpec> = emptyList(),
    val scalar: ParamSpec? = null,
    val openParams: Boolean = false,
)

/**
 * Catálogo interno de componentes que o estúdio sabe editar e validar.
 *
 * IMPORTANTE: foi escrito de memória, sem acesso à documentação oficial. Ele NÃO substitui a documentação
 * da versão-alvo: um componente fora do catálogo gera aviso (e é preservado), nunca erro, e um componente
 * dentro do catálogo pode não existir ou ter outro formato em determinada versão do jogo.
 * Só se declaram limites numéricos quando são óbvios (ex.: luz 0–15, probabilidade 0–1).
 */
object Catalog {
    private fun p(name: String, type: ParamType, required: Boolean = false, min: Double? = null, max: Double? = null, allowed: List<String>? = null) =
        ParamSpec(name, type, required, min, max, allowed)

    private fun c(name: String, vararg params: ParamSpec, scalar: ParamSpec? = null, open: Boolean = false) =
        ComponentSpec(name, params.toList(), scalar, open)

    private val PRIORITY = p("priority", ParamType.INT, required = true, min = 0.0)

    private val entity = listOf(
        c("minecraft:health", p("value", ParamType.NUMBER, true, 0.0), p("max", ParamType.NUMBER, false, 0.0)),
        c("minecraft:movement", p("value", ParamType.NUMBER, true, 0.0)),
        c("minecraft:attack", p("damage", ParamType.ANY, true)),
        c("minecraft:collision_box", p("width", ParamType.NUMBER, false, 0.0), p("height", ParamType.NUMBER, false, 0.0)),
        c("minecraft:type_family", p("family", ParamType.STRING_LIST, true)),
        c("minecraft:scale", p("value", ParamType.NUMBER, true, 0.0)),
        c("minecraft:follow_range", p("value", ParamType.NUMBER, true, 0.0), p("max", ParamType.NUMBER, false, 0.0)),
        c("minecraft:knockback_resistance", p("value", ParamType.NUMBER, true, 0.0, 1.0)),
        c("minecraft:pushable", p("is_pushable", ParamType.BOOL), p("is_pushable_by_piston", ParamType.BOOL)),
        c("minecraft:physics", p("has_gravity", ParamType.BOOL), p("has_collision", ParamType.BOOL)),
        c("minecraft:nameable", open = true),
        c("minecraft:movement.basic"),
        c("minecraft:navigation.walk", p("can_path_over_water", ParamType.BOOL), p("avoid_water", ParamType.BOOL), p("avoid_damage_blocks", ParamType.BOOL), p("can_jump", ParamType.BOOL), p("can_open_doors", ParamType.BOOL), open = true),
        c("minecraft:jump.static", open = true),
        c("minecraft:can_climb"),
        c("minecraft:loot", p("table", ParamType.STRING, true)),
        c("minecraft:behavior.float", PRIORITY),
        c("minecraft:behavior.random_stroll", PRIORITY, p("speed_multiplier", ParamType.NUMBER, false, 0.0), p("xz_dist", ParamType.INT, false, 0.0), p("y_dist", ParamType.INT, false, 0.0), p("interval", ParamType.INT, false, 0.0)),
        c("minecraft:behavior.look_at_player", PRIORITY, p("look_distance", ParamType.NUMBER, false, 0.0), p("probability", ParamType.NUMBER, false, 0.0, 1.0), open = true),
        c("minecraft:behavior.random_look_around", PRIORITY, open = true),
        c("minecraft:behavior.panic", PRIORITY, p("speed_multiplier", ParamType.NUMBER, false, 0.0), open = true),
        c("minecraft:behavior.melee_attack", PRIORITY, p("speed_multiplier", ParamType.NUMBER, false, 0.0), p("track_target", ParamType.BOOL), p("reach_multiplier", ParamType.NUMBER, false, 0.0), open = true),
        c("minecraft:behavior.hurt_by_target", PRIORITY, p("entity_types", ParamType.ANY), p("alert_same_type", ParamType.BOOL), open = true),
        c("minecraft:behavior.nearest_attackable_target", PRIORITY, p("entity_types", ParamType.LIST, true), p("must_see", ParamType.BOOL), p("reselect_targets", ParamType.BOOL), p("attack_interval", ParamType.INT, false, 0.0), open = true),
        c("minecraft:behavior.avoid_mob_type", PRIORITY, p("entity_types", ParamType.LIST), p("max_dist", ParamType.NUMBER, false, 0.0), p("sprint_speed_multiplier", ParamType.NUMBER, false, 0.0), p("walk_speed_multiplier", ParamType.NUMBER, false, 0.0), open = true),
    )

    private val block = listOf(
        c("minecraft:destructible_by_mining", p("seconds_to_destroy", ParamType.NUMBER, false, 0.0), scalar = p("value", ParamType.ANY)),
        c("minecraft:destructible_by_explosion", p("explosion_resistance", ParamType.NUMBER, false, 0.0), scalar = p("value", ParamType.ANY)),
        c("minecraft:friction", scalar = p("value", ParamType.NUMBER, false, 0.0)),
        c("minecraft:light_emission", scalar = p("value", ParamType.INT, false, 0.0, 15.0)),
        c("minecraft:light_dampening", scalar = p("value", ParamType.INT, false, 0.0, 15.0)),
        c("minecraft:flammable", p("catch_chance_modifier", ParamType.INT, false, 0.0), p("destroy_chance_modifier", ParamType.INT, false, 0.0), scalar = p("value", ParamType.ANY)),
        c("minecraft:map_color", scalar = p("value", ParamType.STRING)),
        c("minecraft:display_name", scalar = p("value", ParamType.STRING)),
        c("minecraft:geometry", scalar = p("value", ParamType.STRING), open = true),
        c("minecraft:material_instances", open = true),
        c("minecraft:collision_box", p("origin", ParamType.VEC3), p("size", ParamType.VEC3), scalar = p("value", ParamType.BOOL)),
        c("minecraft:selection_box", p("origin", ParamType.VEC3), p("size", ParamType.VEC3), scalar = p("value", ParamType.BOOL)),
        c("minecraft:loot", scalar = p("value", ParamType.STRING)),
    )

    private val item = listOf(
        c("minecraft:icon", open = true, scalar = p("value", ParamType.STRING)),
        c("minecraft:display_name", p("value", ParamType.STRING), scalar = p("value", ParamType.STRING)),
        c("minecraft:max_stack_size", scalar = p("value", ParamType.INT, false, 1.0, 99.0)),
        c("minecraft:damage", scalar = p("value", ParamType.INT, false, 0.0)),
        c("minecraft:durability", p("max_durability", ParamType.INT, true, 0.0), open = true),
        c("minecraft:food", p("nutrition", ParamType.INT, false, 0.0), p("saturation_modifier", ParamType.NUMBER, false, 0.0), p("can_always_eat", ParamType.BOOL), open = true),
        c("minecraft:hand_equipped", scalar = p("value", ParamType.BOOL)),
        c("minecraft:glint", scalar = p("value", ParamType.BOOL)),
        c("minecraft:enchantable", p("value", ParamType.INT, false, 0.0), p("slot", ParamType.STRING), open = true),
        c("minecraft:wearable", p("slot", ParamType.STRING), open = true),
    )

    private val specs: Map<ComponentKind, Map<String, ComponentSpec>> = mapOf(
        ComponentKind.ENTITY to entity.associateBy { it.name },
        ComponentKind.BLOCK to block.associateBy { it.name },
        ComponentKind.ITEM to item.associateBy { it.name },
    )

    fun spec(kind: ComponentKind, name: String): ComponentSpec? = specs.getValue(kind)[name]
    fun names(kind: ComponentKind): List<String> = specs.getValue(kind).keys.sorted()

    private fun diag(sev: Severity, code: String, msg: String, file: String?) =
        Diagnostic(sev, Category.STRUCTURE, code, msg, file)

    fun validate(kind: ComponentKind, name: String, value: JsonValue, file: String? = null): List<Diagnostic> {
        val spec = spec(kind, name)
        if (spec == null) {
            return listOf(
                Diagnostic(
                    Severity.WARNING, Category.COMPATIBILITY, "UNKNOWN_COMPONENT",
                    "Componente '$name' não consta do catálogo do estúdio; foi mantido como está. Confira na documentação da versão-alvo.", file,
                ),
            )
        }
        val out = mutableListOf<Diagnostic>()
        if (value !is JsonObj) {
            if (spec.scalar != null) checkValue(spec.scalar, value, name, out, file)
            else out.add(diag(Severity.ERROR, "COMPONENT_NOT_OBJECT", "O componente '$name' deve ser um objeto.", file))
            return out
        }
        if (spec.scalar != null) return out // forma de objeto de um componente que também aceita valor simples: não validada
        for (ps in spec.params) {
            val v = value[ps.name]
            if (v == null) {
                if (ps.required) out.add(diag(Severity.ERROR, "PARAM_MISSING", "'$name' exige o parâmetro '${ps.name}'.", file))
            } else {
                checkValue(ps, v, "$name.${ps.name}", out, file)
            }
        }
        if (!spec.openParams) {
            for (k in value.fields.keys) {
                if (spec.params.none { it.name == k }) {
                    out.add(diag(Severity.WARNING, "PARAM_UNKNOWN", "Parâmetro '$k' de '$name' não consta do catálogo; mantido.", file))
                }
            }
        }
        return out
    }

    private fun checkValue(ps: ParamSpec, v: JsonValue, label: String, out: MutableList<Diagnostic>, file: String?) {
        val ok = when (ps.type) {
            ParamType.INT -> v is JsonNum && v.raw.toLongOrNull() != null
            ParamType.NUMBER -> v is JsonNum && v.raw.toDoubleOrNull() != null
            ParamType.BOOL -> v is JsonBool
            ParamType.STRING -> v is JsonStr
            ParamType.STRING_LIST -> v is JsonArr && v.items.all { it is JsonStr }
            ParamType.VEC3 -> v is JsonArr && v.items.size == 3 && v.items.all { it is JsonNum }
            ParamType.LIST -> v is JsonArr
            ParamType.OBJECT -> v is JsonObj
            ParamType.ANY -> true
        }
        if (!ok) {
            out.add(diag(Severity.ERROR, "PARAM_TYPE", "'$label' tem tipo inválido (esperado ${ps.type.name.lowercase()}).", file))
            return
        }
        if (v is JsonNum) {
            val d = v.raw.toDouble()
            if ((ps.min != null && d < ps.min) || (ps.max != null && d > ps.max)) {
                out.add(diag(Severity.ERROR, "PARAM_RANGE", "'$label' fora do intervalo permitido (${ps.min ?: "-∞"} a ${ps.max ?: "∞"}).", file))
            }
        }
        if (v is JsonStr && ps.allowed != null && v.value !in ps.allowed) {
            out.add(diag(Severity.ERROR, "PARAM_VALUE", "'$label' deve ser um de: ${ps.allowed.joinToString()}.", file))
        }
    }

    /** Valida um mapa de componentes. */
    fun validateAll(kind: ComponentKind, components: Map<String, JsonValue>, file: String?): List<Diagnostic> =
        components.flatMap { (n, v) -> validate(kind, n, v, file) }
}
