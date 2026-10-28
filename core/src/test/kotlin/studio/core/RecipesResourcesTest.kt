package studio.core

import studio.core.addon.*
import studio.core.diagnostics.Diagnostic
import studio.core.json.*
import kotlin.test.*

class RecipesResourcesTest {
    private fun codes(l: List<Diagnostic>) = l.map { it.code }
    private val pick = ShapedRecipe(
        "studio:ruby_pickaxe", listOf("RRR", " S ", " S "),
        mapOf('R' to ItemRef("studio:ruby"), 'S' to ItemRef("minecraft:stick")), ItemRef("studio:ruby_pickaxe"),
    )

    @Test fun shapedRecipe() {
        assertTrue(pick.validate().isEmpty(), pick.validate().joinToString())
        val r = pick.toJson().obj("minecraft:recipe_shaped")!!
        assertEquals(3, r.arr("pattern")!!.items.size)
        assertEquals("studio:ruby", r.obj("key")!!.obj("R")!!.str("item"))
        assertEquals("studio:ruby_pickaxe", r.obj("result")!!.str("item"))
        assertEquals("crafting_table", (r.arr("tags")!!.items[0] as JsonStr).value)

        assertTrue("RECIPE_KEY_MISSING" in codes(pick.copy(pattern = listOf("RRX")).validate()))
        assertTrue("RECIPE_PATTERN" in codes(pick.copy(pattern = listOf("R", "R", "R", "R")).validate()))
        assertTrue("RECIPE_PATTERN" in codes(pick.copy(pattern = listOf("RRRR")).validate()))
        assertTrue("RECIPE_KEY_UNUSED" in codes(pick.copy(key = pick.key + ('Z' to ItemRef("minecraft:dirt"))).validate()))
        assertTrue("ITEM_COUNT" in codes(pick.copy(result = ItemRef("studio:x", count = 0)).validate()))
        assertTrue("ITEM_REF" in codes(pick.copy(result = ItemRef("Pickaxe")).validate()))
    }

    @Test fun shapelessAndFurnace() {
        val sl = ShapelessRecipe("studio:dye", listOf(ItemRef("minecraft:dirt"), ItemRef("minecraft:sand", data = 1)), ItemRef("studio:mix", count = 2))
        assertTrue(sl.validate().isEmpty())
        assertEquals(2, sl.toJson().obj("minecraft:recipe_shapeless")!!.arr("ingredients")!!.items.size)
        assertTrue("RECIPE_INGREDIENTS" in codes(sl.copy(ingredients = List(10) { ItemRef("minecraft:dirt") }).validate()))
        val f = FurnaceRecipe("studio:smelt_ruby", "studio:ruby_ore", "studio:ruby")
        assertTrue(f.validate().isEmpty())
        assertEquals("studio:ruby", f.toJson().obj("minecraft:recipe_furnace")!!.str("output"))
        assertTrue("ITEM_REF" in codes(f.copy(input = "Minério").validate()))
    }

    @Test fun lootTables() {
        val t = LootTable(listOf(LootPool(1, 1, listOf(LootEntry("item", "studio:ruby", 5, listOf(LootFunction.setCount(1, 3)))))))
        assertTrue(t.validate().isEmpty(), t.validate().joinToString())
        val pool = t.toJson().arr("pools")!!.items[0] as JsonObj
        assertEquals("1", (pool["rolls"] as JsonNum).raw)
        val entry = pool.arr("entries")!!.items[0] as JsonObj
        assertEquals("set_count", ((entry.arr("functions")!!.items[0]) as JsonObj).str("function"))
        val ranged = LootTable(listOf(LootPool(1, 3, listOf(LootEntry("empty")))))
        assertTrue(ranged.toJson().arr("pools")!!.items[0].let { (it as JsonObj).obj("rolls") } != null)

        assertTrue("LOOT_POOLS" in codes(LootTable(emptyList()).validate()))
        assertTrue("LOOT_ROLLS" in codes(LootTable(listOf(LootPool(3, 1, listOf(LootEntry("empty"))))).validate()))
        assertTrue("LOOT_WEIGHT" in codes(LootTable(listOf(LootPool(1, 1, listOf(LootEntry("item", "a:b", 0))))).validate()))
        assertTrue("LOOT_TYPE" in codes(LootTable(listOf(LootPool(1, 1, listOf(LootEntry("magia"))))).validate()))
        assertTrue("LOOT_NAME" in codes(LootTable(listOf(LootPool(1, 1, listOf(LootEntry("item"))))).validate()))
        assertTrue("LOOT_SET_COUNT" in codes(LootTable(listOf(LootPool(1, 1, listOf(LootEntry("item", "a:b", null, listOf(LootFunction.setCount(5, 2))))))).validate()))
        assertTrue("LOOT_FUNCTION_UNKNOWN" in codes(LootTable(listOf(LootPool(1, 1, listOf(LootEntry("item", "a:b", null, listOf(LootFunction("função_nova"))))))).validate()))
    }

    @Test fun spawnRules() {
        val s = SpawnRulesDef("studio:golem", "animal", listOf(SpawnCondition(blockFilter = listOf("minecraft:grass_block"), brightnessMin = 7, brightnessMax = 15, herdMin = 2, herdMax = 4, biomeTag = "plains")))
        assertTrue(s.validate().isEmpty(), s.validate().joinToString())
        val c = s.toJson().obj("minecraft:spawn_rules")!!.arr("conditions")!!.items[0] as JsonObj
        assertNotNull(c["minecraft:spawns_on_surface"])
        assertEquals("2", (c.obj("minecraft:herd")!!["min_size"] as JsonNum).raw)
        assertTrue("SPAWN_HERD" in codes(s.copy(conditions = listOf(SpawnCondition(herdMin = 2))).validate()))
        assertTrue("SPAWN_BRIGHTNESS" in codes(s.copy(conditions = listOf(SpawnCondition(brightnessMin = 20, brightnessMax = 30))).validate()))
        assertTrue("SPAWN_CONDITIONS" in codes(s.copy(conditions = emptyList()).validate()))
    }

    @Test fun atlasMergePreservesAndAdds() {
        val fresh = Json.parseObject(Atlas.mergeTerrain(null, "meu_rp", "ruby_block", "textures/blocks/ruby_block"))
        assertEquals("atlas.terrain", fresh.str("texture_name"))
        assertEquals("textures/blocks/ruby_block", fresh.obj("texture_data")!!.obj("ruby_block")!!.str("textures"))
        val existing = """{"resource_pack_name":"x","x_extra":1,"texture_data":{"old":{"textures":"textures/blocks/old"}}}"""
        val merged = Json.parseObject(Atlas.mergeItem(existing, "x", "ruby", "textures/items/ruby"))
        assertEquals("1", (merged["x_extra"] as JsonNum).raw)
        assertNotNull(merged.obj("texture_data")!!.obj("old"))
        assertNotNull(merged.obj("texture_data")!!.obj("ruby"))
        assertFailsWith<IllegalArgumentException> { Atlas.mergeItem(null, "x", "Nome Ruim", "a/b") }
        assertFailsWith<IllegalArgumentException> { Atlas.mergeItem(null, "x", "ok", "a/b.png") }
        assertFailsWith<JsonParseException> { Atlas.mergeItem("{ quebrado", "x", "ok", "a/b") }
    }

    @Test fun langMerge() {
        assertEquals("## c\na=1\nb=3\nc=4\n", LangFile.merge("## c\na=1\nb=2\n", linkedMapOf("b" to "3", "c" to "4")))
        assertEquals("a=1\n", LangFile.merge(null, mapOf("a" to "1")))
        assertEquals("x=1\ny=2\n", LangFile.merge("x=1\n\n\n", mapOf("y" to "2")))
        assertFailsWith<IllegalArgumentException> { LangFile.merge(null, mapOf("a" to "linha1\nlinha2")) }
        assertFailsWith<IllegalArgumentException> { LangFile.merge(null, mapOf("a=b" to "x")) }
        assertEquals("tile.studio:ruby_block.name", LangFile.blockKey("studio:ruby_block"))
    }
}
