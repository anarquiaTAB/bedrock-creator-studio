package studio.core.addon

import studio.core.diagnostics.Diagnostic
import studio.core.json.*

/** Referência a um item: "namespace:nome", com dado (data) e quantidade opcionais. */
data class ItemRef(val item: String, val data: Int? = null, val count: Int? = null) {
    fun validate(label: String, file: String?): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        if (!IDENT.matches(item)) out.add(err("ITEM_REF", "$label: identificador de item inválido '$item'.", file))
        if (data != null && data < 0) out.add(err("ITEM_DATA", "$label: o valor de dado não pode ser negativo.", file))
        if (count != null && (count < 1 || count > 64)) out.add(err("ITEM_COUNT", "$label: a quantidade deve estar entre 1 e 64.", file))
        return out
    }

    fun toJson(): JsonObj {
        val m = LinkedHashMap<String, JsonValue>()
        m["item"] = js(item)
        if (data != null) m["data"] = jn(data)
        if (count != null) m["count"] = jn(count)
        return JsonObj(m)
    }
}

sealed interface RecipeDef {
    val identifier: String
    fun validate(file: String? = null): List<Diagnostic>
    fun toJson(v: FormatVersions = FormatVersions()): JsonObj
}

data class ShapedRecipe(
    override val identifier: String,
    val pattern: List<String>,
    val key: Map<Char, ItemRef>,
    val result: ItemRef,
    val tags: List<String> = listOf("crafting_table"),
) : RecipeDef {
    override fun validate(file: String?): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += checkIdentifier(identifier, file)
        if (pattern.isEmpty() || pattern.size > 3) out.add(err("RECIPE_PATTERN", "O padrão deve ter de 1 a 3 linhas.", file))
        for ((i, row) in pattern.withIndex()) {
            if (row.isEmpty() || row.length > 3) out.add(err("RECIPE_PATTERN", "A linha ${i + 1} do padrão deve ter de 1 a 3 caracteres.", file))
        }
        val used = pattern.flatMap { it.toList() }.filter { it != ' ' }.toSet()
        for (c in used) if (c !in key) out.add(err("RECIPE_KEY_MISSING", "O símbolo '$c' do padrão não está definido em \"key\".", file))
        for (c in key.keys) {
            if (c == ' ') out.add(err("RECIPE_KEY_SPACE", "O espaço não pode ser usado como símbolo.", file))
            if (c !in used) out.add(warn("RECIPE_KEY_UNUSED", "O símbolo '$c' está definido mas não é usado no padrão.", file))
        }
        for ((c, ref) in key) out += ref.validate("Símbolo '$c'", file)
        out += result.validate("Resultado", file)
        if (tags.isEmpty()) out.add(err("RECIPE_TAGS", "Informe ao menos uma tag (ex.: crafting_table).", file))
        return out
    }

    override fun toJson(v: FormatVersions): JsonObj {
        val k = LinkedHashMap<String, JsonValue>()
        for ((c, ref) in key) k[c.toString()] = ref.toJson()
        return jsonObj(
            "format_version" to js(v.recipe),
            "minecraft:recipe_shaped" to jsonObj(
                "description" to jsonObj("identifier" to js(identifier)),
                "tags" to jstrings(tags),
                "pattern" to jstrings(pattern),
                "key" to JsonObj(k),
                "result" to result.toJson(),
            ),
        )
    }
}

data class ShapelessRecipe(
    override val identifier: String,
    val ingredients: List<ItemRef>,
    val result: ItemRef,
    val tags: List<String> = listOf("crafting_table"),
) : RecipeDef {
    override fun validate(file: String?): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += checkIdentifier(identifier, file)
        if (ingredients.isEmpty() || ingredients.size > 9) out.add(err("RECIPE_INGREDIENTS", "Use de 1 a 9 ingredientes.", file))
        ingredients.forEachIndexed { i, r -> out += r.validate("Ingrediente ${i + 1}", file) }
        out += result.validate("Resultado", file)
        if (tags.isEmpty()) out.add(err("RECIPE_TAGS", "Informe ao menos uma tag (ex.: crafting_table).", file))
        return out
    }

    override fun toJson(v: FormatVersions): JsonObj = jsonObj(
        "format_version" to js(v.recipe),
        "minecraft:recipe_shapeless" to jsonObj(
            "description" to jsonObj("identifier" to js(identifier)),
            "tags" to jstrings(tags),
            "ingredients" to JsonArr(ingredients.map { it.toJson() }),
            "result" to result.toJson(),
        ),
    )
}

data class FurnaceRecipe(
    override val identifier: String,
    val input: String,
    val output: String,
    val tags: List<String> = listOf("furnace"),
) : RecipeDef {
    override fun validate(file: String?): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += checkIdentifier(identifier, file)
        if (!IDENT.matches(input)) out.add(err("ITEM_REF", "Entrada inválida '$input'.", file))
        if (!IDENT.matches(output)) out.add(err("ITEM_REF", "Saída inválida '$output'.", file))
        if (tags.isEmpty()) out.add(err("RECIPE_TAGS", "Informe ao menos uma tag (ex.: furnace).", file))
        return out
    }

    override fun toJson(v: FormatVersions): JsonObj = jsonObj(
        "format_version" to js(v.recipe),
        "minecraft:recipe_furnace" to jsonObj(
            "description" to jsonObj("identifier" to js(identifier)),
            "tags" to jstrings(tags),
            "input" to js(input),
            "output" to js(output),
        ),
    )
}

// ---------------- Tabelas de saque ----------------

data class LootFunction(val function: String, val params: JsonObj = JsonObj.empty()) {
    fun toJson(): JsonObj {
        val m = LinkedHashMap<String, JsonValue>()
        m["function"] = js(function)
        m.putAll(params.fields)
        return JsonObj(m)
    }

    companion object {
        fun setCount(min: Int, max: Int) = LootFunction("set_count", jsonObj("count" to jsonObj("min" to jn(min), "max" to jn(max))))
    }
}

data class LootEntry(val type: String, val name: String? = null, val weight: Int? = null, val functions: List<LootFunction> = emptyList())

data class LootPool(val rollsMin: Int, val rollsMax: Int = rollsMin, val entries: List<LootEntry>)

data class LootTable(val pools: List<LootPool>) {
    fun validate(file: String? = null): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        if (pools.isEmpty()) out.add(err("LOOT_POOLS", "A tabela precisa de ao menos uma pool.", file))
        for ((pi, pool) in pools.withIndex()) {
            val where = "Pool ${pi + 1}"
            if (pool.rollsMin < 0 || pool.rollsMax < pool.rollsMin) out.add(err("LOOT_ROLLS", "$where: rolls inválido (mínimo ≥ 0 e máximo ≥ mínimo).", file))
            if (pool.entries.isEmpty()) out.add(err("LOOT_ENTRIES", "$where: sem entradas.", file))
            for ((ei, e) in pool.entries.withIndex()) {
                val label = "$where, entrada ${ei + 1}"
                when (e.type) {
                    "item", "loot_table" -> if (e.name == null || e.name.isBlank()) out.add(err("LOOT_NAME", "$label: informe o nome.", file))
                        else if (e.type == "item" && !IDENT.matches(e.name)) out.add(err("LOOT_NAME", "$label: identificador de item inválido '${e.name}'.", file))
                    "empty" -> {}
                    else -> out.add(err("LOOT_TYPE", "$label: tipo desconhecido '${e.type}' (use item, loot_table ou empty).", file))
                }
                if (e.weight != null && e.weight < 1) out.add(err("LOOT_WEIGHT", "$label: o peso deve ser ≥ 1.", file))
                for (f in e.functions) {
                    if (f.function == "set_count") {
                        val c = f.params.obj("count")
                        val mn = c?.int("min")
                        val mx = c?.int("max")
                        if (c == null || mn == null || mx == null) out.add(err("LOOT_SET_COUNT", "$label: set_count precisa de count.min e count.max inteiros.", file))
                        else if (mn < 0 || mx < mn) out.add(err("LOOT_SET_COUNT", "$label: set_count com intervalo inválido.", file))
                    } else if (f.function !in KNOWN_FUNCTIONS) {
                        out.add(warn("LOOT_FUNCTION_UNKNOWN", "$label: função '${f.function}' não conhecida pelo estúdio; mantida.", file))
                    }
                }
            }
        }
        return out
    }

    fun toJson(): JsonObj = jsonObj(
        "pools" to JsonArr(
            pools.map { p ->
                val rolls: JsonValue = if (p.rollsMin == p.rollsMax) jn(p.rollsMin) else jsonObj("min" to jn(p.rollsMin), "max" to jn(p.rollsMax))
                jsonObj(
                    "rolls" to rolls,
                    "entries" to JsonArr(
                        p.entries.map { e ->
                            val m = LinkedHashMap<String, JsonValue>()
                            m["type"] = js(e.type)
                            if (e.name != null) m["name"] = js(e.name)
                            if (e.weight != null) m["weight"] = jn(e.weight)
                            if (e.functions.isNotEmpty()) m["functions"] = JsonArr(e.functions.map { it.toJson() })
                            JsonObj(m)
                        },
                    ),
                )
            },
        ),
    )

    companion object {
        private val KNOWN_FUNCTIONS = setOf("set_count", "looting_enchant", "furnace_smelt", "set_damage", "set_data", "set_name", "enchant_randomly")
    }
}

// ---------------- Regras de geração ----------------

data class SpawnCondition(
    val onSurface: Boolean = true,
    val blockFilter: List<String> = emptyList(),
    val brightnessMin: Int? = null,
    val brightnessMax: Int? = null,
    val weight: Int = 100,
    val herdMin: Int? = null,
    val herdMax: Int? = null,
    val biomeTag: String? = null,
)

data class SpawnRulesDef(val identifier: String, val populationControl: String, val conditions: List<SpawnCondition>) {
    fun validate(file: String? = null): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += checkIdentifier(identifier, file)
        if (populationControl.isBlank()) out.add(err("SPAWN_POPULATION", "Informe o population_control.", file))
        if (conditions.isEmpty()) out.add(err("SPAWN_CONDITIONS", "Informe ao menos uma condição.", file))
        for ((i, c) in conditions.withIndex()) {
            val w = "Condição ${i + 1}"
            if (c.weight < 1) out.add(err("SPAWN_WEIGHT", "$w: peso deve ser ≥ 1.", file))
            val bmin = c.brightnessMin
            val bmax = c.brightnessMax
            if ((bmin != null && bmin !in 0..15) || (bmax != null && bmax !in 0..15) || (bmin != null && bmax != null && bmin > bmax)) {
                out.add(err("SPAWN_BRIGHTNESS", "$w: brilho deve estar entre 0 e 15 com mínimo ≤ máximo.", file))
            }
            val hmin = c.herdMin
            val hmax = c.herdMax
            if ((hmin != null && hmin < 1) || (hmin != null && hmax != null && hmax < hmin) || ((hmin == null) != (hmax == null))) {
                out.add(err("SPAWN_HERD", "$w: informe herdMin e herdMax juntos, com 1 ≤ mín ≤ máx.", file))
            }
            for (b in c.blockFilter) if (!IDENT.matches(b)) out.add(err("SPAWN_BLOCK", "$w: bloco inválido '$b'.", file))
        }
        return out
    }

    fun toJson(v: FormatVersions = FormatVersions()): JsonObj {
        val conds = conditions.map { c ->
            val m = LinkedHashMap<String, JsonValue>()
            if (c.onSurface) m["minecraft:spawns_on_surface"] = JsonObj.empty()
            if (c.blockFilter.isNotEmpty()) m["minecraft:spawns_on_block_filter"] = jstrings(c.blockFilter)
            if (c.brightnessMin != null && c.brightnessMax != null) {
                m["minecraft:brightness_filter"] = jsonObj("min" to jn(c.brightnessMin), "max" to jn(c.brightnessMax), "adjust_for_weather" to jb(false))
            }
            m["minecraft:weight"] = jsonObj("default" to jn(c.weight))
            if (c.herdMin != null && c.herdMax != null) m["minecraft:herd"] = jsonObj("min_size" to jn(c.herdMin), "max_size" to jn(c.herdMax))
            if (c.biomeTag != null) m["minecraft:biome_filter"] = jsonObj("test" to js("has_biome_tag"), "operator" to js("=="), "value" to js(c.biomeTag))
            JsonObj(m)
        }
        return jsonObj(
            "format_version" to js(v.spawnRules),
            "minecraft:spawn_rules" to jsonObj(
                "description" to jsonObj("identifier" to js(identifier), "population_control" to js(populationControl)),
                "conditions" to JsonArr(conds),
            ),
        )
    }
}
