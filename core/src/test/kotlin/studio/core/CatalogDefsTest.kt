package studio.core

import studio.core.addon.*
import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity
import studio.core.json.*
import kotlin.test.*

class CatalogDefsTest {
    private fun codes(l: List<Diagnostic>) = l.map { it.code }
    private fun v(json: String) = Json.parse(json)
    private fun errors(l: List<Diagnostic>) = l.filter { it.severity == Severity.ERROR }

    @Test fun presetsAreConsistentWithTheCatalog() {
        for (preset in listOf(EntityPresets.hostileMelee(), EntityPresets.passiveWanderer())) {
            val d = Catalog.validateAll(ComponentKind.ENTITY, preset, "x")
            assertTrue(d.isEmpty(), d.joinToString())
        }
    }

    @Test fun catalogChecksTypesRangesAndRequiredParams() {
        val E = ComponentKind.ENTITY
        assertEquals(listOf("PARAM_TYPE"), codes(Catalog.validate(E, "minecraft:health", v("""{"value":"x"}"""))))
        assertEquals(listOf("PARAM_RANGE"), codes(Catalog.validate(E, "minecraft:knockback_resistance", v("""{"value":2}"""))))
        assertEquals(listOf("PARAM_MISSING"), codes(Catalog.validate(E, "minecraft:behavior.float", v("{}"))))
        assertEquals(listOf("PARAM_UNKNOWN"), codes(Catalog.validate(E, "minecraft:health", v("""{"value":1,"banana":2}"""))))
        assertEquals(listOf("COMPONENT_NOT_OBJECT"), codes(Catalog.validate(E, "minecraft:health", v("5"))))
        val unknown = Catalog.validate(E, "minecraft:inventada", v("{}")).single()
        assertEquals("UNKNOWN_COMPONENT", unknown.code)
        assertEquals(Severity.WARNING, unknown.severity)
        assertEquals(Category.COMPATIBILITY, unknown.category)
    }

    @Test fun scalarComponentsAcceptValuesAndObjects() {
        val B = ComponentKind.BLOCK
        assertTrue(Catalog.validate(B, "minecraft:light_emission", v("15")).isEmpty())
        assertEquals(listOf("PARAM_RANGE"), codes(Catalog.validate(B, "minecraft:light_emission", v("16"))))
        assertEquals(listOf("PARAM_TYPE"), codes(Catalog.validate(B, "minecraft:light_emission", v("\"alto\""))))
        assertTrue(Catalog.validate(B, "minecraft:friction", v("""{"qualquer":1}""")).isEmpty())
        assertTrue(Catalog.validate(ComponentKind.ITEM, "minecraft:max_stack_size", v("64")).isEmpty())
        assertEquals(listOf("PARAM_RANGE"), codes(Catalog.validate(ComponentKind.ITEM, "minecraft:max_stack_size", v("0"))))
        assertTrue("minecraft:health" in Catalog.names(ComponentKind.ENTITY))
    }

    @Test fun entityJsonShapeAndEventGroupReferences() {
        val ev = Json.parseObject("""{"add":{"component_groups":["angry"]}}""")
        val ok = EntityDef(
            "studio:golem", components = EntityPresets.passiveWanderer(),
            componentGroups = mapOf("angry" to mapOf("minecraft:attack" to jsonObj("damage" to jn(4)))),
            events = mapOf("studio:anger" to ev),
        )
        assertTrue(errors(ok.validate()).isEmpty())
        val json = ok.toJson()
        assertEquals("1.21.0", json.str("format_version"))
        val body = json.obj("minecraft:entity")!!
        assertEquals("studio:golem", body.obj("description")!!.str("identifier"))
        assertNotNull(body.obj("component_groups")!!.obj("angry"))
        assertNotNull(body.obj("events")!!.obj("studio:anger"))
        assertEquals(json, Json.parseObject(Json.write(json)))

        val missing = ok.copy(componentGroups = emptyMap())
        assertTrue("EVENT_GROUP_MISSING" in codes(missing.validate()))
        val bare = EntityDef("studio:bare").toJson().obj("minecraft:entity")!!
        assertNull(bare["component_groups"])
        assertNull(bare["events"])
    }

    @Test fun identifierRules() {
        assertTrue("IDENT_INVALID" in codes(EntityDef("Golem").validate()))
        assertTrue("IDENT_INVALID" in codes(EntityDef("sem_namespace").validate()))
        assertEquals(Severity.WARNING, EntityDef("minecraft:zombie2").validate().single().severity)
        assertTrue(EntityDef("studio:ok_1.a-b/c").validate().isEmpty())
    }

    @Test fun clientEntityAndRenderController() {
        val rc = RenderControllerDef("controller.render.golem")
        val client = ClientEntityDef(
            "studio:golem", mapOf("default" to "textures/entity/golem"), mapOf("default" to "geometry.golem"),
            listOf(rc.name), spawnEggColors = "#FF0000" to "#00FF00",
        )
        assertTrue(client.validate().isEmpty())
        val desc = client.toJson().obj("minecraft:client_entity")!!.obj("description")!!
        assertEquals("textures/entity/golem", desc.obj("textures")!!.str("default"))
        assertEquals("#FF0000", desc.obj("spawn_egg")!!.str("base_color"))
        val r = rc.toJson().obj("render_controllers")!!.obj("controller.render.golem")!!
        assertEquals("Geometry.default", r.str("geometry"))

        val bad = client.copy(textures = emptyMap(), renderControllers = listOf("golem"), spawnEggColors = "vermelho" to "#00FF00")
        val c = codes(bad.validate())
        assertTrue("CLIENT_TEXTURES" in c && "RENDER_CONTROLLER_NAME" in c && "SPAWN_EGG_COLOR" in c)
        assertTrue("TEXTURE_EXTENSION" in codes(client.copy(textures = mapOf("default" to "a/b.png")).validate()))
        assertTrue("RENDER_CONTROLLER_NAME" in codes(RenderControllerDef("x").validate()))
    }

    @Test fun blockStatesPermutationsAndCategory() {
        val ok = BlockDef(
            "studio:lamp", "construction", "itemGroup.name.glass",
            components = mapOf("minecraft:light_emission" to jn(10)),
            states = mapOf("studio:level" to listOf(jn(0), jn(1), jn(2))),
            permutations = listOf(Permutation("query.block_state('studio:level') == 2", mapOf("minecraft:light_emission" to jn(15)))),
        )
        assertTrue(ok.validate().isEmpty(), ok.validate().joinToString())
        val desc = ok.toJson().obj("minecraft:block")!!.obj("description")!!
        assertEquals("construction", desc.obj("menu_category")!!.str("category"))
        assertEquals(3, desc.obj("states")!!.arr("studio:level")!!.items.size)
        assertEquals(1, ok.toJson().obj("minecraft:block")!!.arr("permutations")!!.items.size)

        val bad = ok.copy(
            category = "outra",
            states = mapOf("nivel" to listOf(jn(0), js("a")), "studio:b" to emptyList(), "studio:c" to listOf(jn(1), jn(1))),
            permutations = listOf(Permutation(" ", mapOf("minecraft:light_emission" to jn(99)))),
        )
        val c = codes(bad.validate())
        for (x in listOf("MENU_CATEGORY", "STATE_NAME", "STATE_TYPES", "STATE_VALUES", "STATE_DUPLICATE", "PERMUTATION_CONDITION", "PARAM_RANGE")) assertTrue(x in c, "faltou $x em $c")
    }

    @Test fun itemJsonAndValidation() {
        val item = ItemDef("studio:ruby", components = mapOf("minecraft:max_stack_size" to jn(16), "minecraft:icon" to jsonObj("textures" to jsonObj("default" to js("ruby")))))
        assertTrue(item.validate().isEmpty(), item.validate().joinToString())
        val body = item.toJson().obj("minecraft:item")!!
        assertEquals("items", body.obj("description")!!.obj("menu_category")!!.str("category"))
        assertEquals("16", (body.obj("components")!!["minecraft:max_stack_size"] as JsonNum).raw)
        assertTrue("PARAM_RANGE" in codes(item.copy(components = mapOf("minecraft:max_stack_size" to jn(0))).validate()))
    }
}
