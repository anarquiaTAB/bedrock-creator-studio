package studio.core.addon

import studio.core.json.*

fun jn(v: Int): JsonNum = JsonNum(v.toString())

fun jn(v: Double): JsonNum {
    require(v.isFinite()) { "Número inválido" }
    return JsonNum(v.toString())
}

fun jb(v: Boolean) = JsonBool(v)
fun js(v: String) = JsonStr(v)
fun ja(vararg items: JsonValue) = JsonArr(items.toList())
fun jstrings(items: List<String>) = JsonArr(items.map { JsonStr(it) })

/** Componentes prontos (validados contra o catálogo nos testes). */
object EntityPresets {
    private fun target(family: String, maxDist: Int) = ja(
        jsonObj(
            "filters" to jsonObj("test" to js("is_family"), "subject" to js("other"), "value" to js(family)),
            "max_dist" to jn(maxDist),
        ),
    )

    private fun base(health: Double, speed: Double): LinkedHashMap<String, JsonValue> {
        val m = LinkedHashMap<String, JsonValue>()
        m["minecraft:health"] = jsonObj("value" to jn(health), "max" to jn(health))
        m["minecraft:movement"] = jsonObj("value" to jn(speed))
        m["minecraft:collision_box"] = jsonObj("width" to jn(0.6), "height" to jn(1.8))
        m["minecraft:physics"] = JsonObj.empty()
        m["minecraft:movement.basic"] = JsonObj.empty()
        m["minecraft:navigation.walk"] = jsonObj("can_path_over_water" to jb(true))
        m["minecraft:jump.static"] = JsonObj.empty()
        return m
    }

    fun hostileMelee(health: Double = 20.0, damage: Double = 3.0, speed: Double = 0.25): Map<String, JsonValue> {
        val m = base(health, speed)
        m["minecraft:type_family"] = jsonObj("family" to jstrings(listOf("monster")))
        m["minecraft:attack"] = jsonObj("damage" to jn(damage))
        m["minecraft:behavior.float"] = jsonObj("priority" to jn(0))
        m["minecraft:behavior.hurt_by_target"] = jsonObj("priority" to jn(1))
        m["minecraft:behavior.melee_attack"] = jsonObj("priority" to jn(2), "speed_multiplier" to jn(1.0), "track_target" to jb(true))
        m["minecraft:behavior.nearest_attackable_target"] = jsonObj(
            "priority" to jn(2), "must_see" to jb(true), "entity_types" to target("player", 16),
        )
        m["minecraft:behavior.random_stroll"] = jsonObj("priority" to jn(6), "speed_multiplier" to jn(1.0))
        m["minecraft:behavior.look_at_player"] = jsonObj("priority" to jn(7), "look_distance" to jn(6.0))
        m["minecraft:behavior.random_look_around"] = jsonObj("priority" to jn(7))
        return m
    }

    fun passiveWanderer(health: Double = 10.0, speed: Double = 0.2): Map<String, JsonValue> {
        val m = base(health, speed)
        m["minecraft:type_family"] = jsonObj("family" to jstrings(listOf("animal")))
        m["minecraft:behavior.float"] = jsonObj("priority" to jn(0))
        m["minecraft:behavior.panic"] = jsonObj("priority" to jn(1), "speed_multiplier" to jn(1.25))
        m["minecraft:behavior.random_stroll"] = jsonObj("priority" to jn(6), "speed_multiplier" to jn(1.0))
        m["minecraft:behavior.look_at_player"] = jsonObj("priority" to jn(7), "look_distance" to jn(6.0))
        m["minecraft:behavior.random_look_around"] = jsonObj("priority" to jn(9))
        return m
    }
}
