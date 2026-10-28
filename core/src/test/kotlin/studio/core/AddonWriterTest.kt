package studio.core

import studio.core.addon.*
import studio.core.diagnostics.Severity
import studio.core.json.*
import studio.core.manifest.*
import studio.core.pack.Packager
import studio.core.project.Project
import studio.core.project.ProjectStore
import java.nio.file.Files
import kotlin.test.*

class AddonWriterTest {
    private val ENTITY = "packs/BP/entities/ruby_golem.json"

    private fun setup(base: java.nio.file.Path): Project {
        val p = ProjectStore(base).create("Ruby", "1.21.0")
        val rp = ManifestSpec(PackKind.RESOURCES, "Ruby RP", minEngineVersion = listOf(1, 21, 0))
        val bp = ManifestSpec(
            PackKind.BEHAVIOR, "Ruby BP", minEngineVersion = listOf(1, 21, 0),
            dependencies = listOf(PackDependency.OnPack(rp.headerUuid, rp.version), PackDependency.OnModule("@minecraft/server", "1.0.0")),
            scriptEntry = "scripts/main.js",
        )
        p.writeText("packs/RP/manifest.json", Json.write(ManifestBuilder.build(rp)))
        p.writeText("packs/BP/manifest.json", Json.write(ManifestBuilder.build(bp)))
        return p
    }

    private fun golem() = EntityDef("studio:ruby_golem", components = EntityPresets.hostileMelee())
    private fun client() = ClientEntityDef("studio:ruby_golem", mapOf("default" to "textures/entity/ruby_golem"), mapOf("default" to "geometry.ruby_golem"), listOf("controller.render.ruby_golem"))

    @Test fun fullAddonFlowProducesFilesThatDiagnoseAndPackage() = withTempDir { d ->
        val p = setup(d.resolve("projects"))
        val w = AddonWriter(p)
        val e = w.writeEntity(golem(), client(), RenderControllerDef("controller.render.ruby_golem"), "Ruby Golem", "Ruby Golem Spawn Egg")
        assertTrue(e.ok, e.diagnostics.joinToString())
        assertEquals(4, e.written.size)
        val b = w.writeBlock(BlockDef("studio:ruby_block", components = mapOf("minecraft:light_emission" to jn(7))), "Ruby Block", "ruby_block" to "textures/blocks/ruby_block")
        assertTrue(b.ok, b.diagnostics.joinToString())
        assertTrue(w.writeItem(ItemDef("studio:ruby", components = mapOf("minecraft:max_stack_size" to jn(64))), "Ruby", "ruby" to "textures/items/ruby").ok)
        assertTrue(w.writeRecipe(ShapedRecipe("studio:ruby_block_recipe", listOf("RRR", "RRR", "RRR"), mapOf('R' to ItemRef("studio:ruby")), ItemRef("studio:ruby_block"))).ok)
        assertTrue(w.writeLootTable("entities/ruby_golem", LootTable(listOf(LootPool(1, 1, listOf(LootEntry("item", "studio:ruby", 1, listOf(LootFunction.setCount(1, 3)))))))).ok)
        assertTrue(w.writeSpawnRules(SpawnRulesDef("studio:ruby_golem", "monster", listOf(SpawnCondition()))).ok)
        assertTrue(w.writeFunction("util/hello", "say oi\n").ok)
        assertTrue(w.writeScript().ok)

        val lang = p.readText("packs/RP/texts/en_US.lang")
        assertTrue(lang.contains("entity.studio:ruby_golem.name=Ruby Golem"))
        assertTrue(lang.contains("tile.studio:ruby_block.name=Ruby Block"))
        assertTrue(lang.contains("item.studio:ruby=Ruby"))
        val terrain = Json.parseObject(p.readText("packs/RP/textures/terrain_texture.json"))
        assertEquals("Ruby RP", terrain.str("resource_pack_name"))
        for (f in p.listFiles().filter { it.endsWith(".json") }) Json.parse(p.readText(f))

        val report = p.diagnose()
        assertFalse(report.hasErrors, report.errors.joinToString())
        assertTrue(report.warnings.any { it.code == "REF_GEOMETRY_MISSING" })
        val out = d.resolve("ruby.mcaddon")
        assertNotNull(Packager.exportMcaddon(p.packDirs(), out).output)
    }

    @Test fun refusesInvalidDefinitionsAndExistingFilesWithoutWritingAnything() = withTempDir { d ->
        val p = setup(d.resolve("projects"))
        val w = AddonWriter(p)
        val bad = w.writeEntity(EntityDef("Golem Ruim"), client())
        assertFalse(bad.ok)
        assertTrue(bad.written.isEmpty())
        assertFalse(p.exists(ENTITY))
        assertTrue(bad.diagnostics.any { it.code == "IDENT_INVALID" })

        assertTrue(w.writeEntity(golem()).ok)
        val before = p.readText(ENTITY)
        val again = w.writeEntity(golem().copy(isSpawnable = false), client())
        assertFalse(again.ok)
        assertTrue(again.diagnostics.any { it.code == "FILE_EXISTS" })
        assertEquals(before, p.readText(ENTITY))
        assertFalse(p.exists("packs/RP/entity/ruby_golem.entity.json")) // nada foi gravado, nem os arquivos novos
        assertTrue(AddonWriter(p, overwrite = true).writeEntity(golem().copy(isSpawnable = false), client()).ok)
        assertNotEquals(before, p.readText(ENTITY))
    }

    @Test fun mergeFailureLeavesEverythingUntouched() = withTempDir { d ->
        val p = setup(d.resolve("projects"))
        p.writeText("packs/RP/textures/terrain_texture.json", "{ quebrado")
        val r = AddonWriter(p).writeBlock(BlockDef("studio:ruby_block"), "Ruby Block", "ruby_block" to "textures/blocks/ruby_block")
        assertFalse(r.ok)
        assertTrue(r.diagnostics.any { it.code == "MERGE_FAILED" })
        assertFalse(p.exists("packs/BP/blocks/ruby_block.json"))
        assertEquals("{ quebrado", p.readText("packs/RP/textures/terrain_texture.json"))
    }

    @Test fun visualEditsUpdateTheRealFileAndKeepUnknownData() = withTempDir { d ->
        val p = setup(d.resolve("projects"))
        assertTrue(AddonWriter(p).writeEntity(golem()).ok)
        p.updateJson(ENTITY) { it.with("x_unknown", jsonObj("a" to jn(1))) } // dado que o estúdio não conhece

        assertTrue(AddonEdit.setComponent(p, ENTITY, "minecraft:health", jsonObj("value" to jn(40), "max" to jn(40))).none { it.severity == Severity.ERROR })
        val comps = AddonEdit.listComponents(p, ENTITY)
        assertEquals("40", ((comps["minecraft:health"] as JsonObj)["value"] as JsonNum).raw)
        val doc = Json.parseObject(p.readText(ENTITY))
        assertNotNull(doc["x_unknown"])
        assertEquals("studio:ruby_golem", doc.obj("minecraft:entity")!!.obj("description")!!.str("identifier"))

        val before = p.readText(ENTITY)
        val rejected = AddonEdit.setComponent(p, ENTITY, "minecraft:knockback_resistance", jsonObj("value" to jn(5)))
        assertTrue(rejected.any { it.severity == Severity.ERROR })
        assertEquals(before, p.readText(ENTITY))

        val unknown = AddonEdit.setComponent(p, ENTITY, "minecraft:inventada", JsonObj.empty())
        assertEquals(Severity.WARNING, unknown.single().severity)
        assertNotNull(AddonEdit.listComponents(p, ENTITY)["minecraft:inventada"])
        assertTrue(AddonEdit.removeComponent(p, ENTITY, "minecraft:inventada"))
        assertFalse(AddonEdit.removeComponent(p, ENTITY, "minecraft:inventada"))
        assertFailsWith<studio.core.storage.StorageException> { AddonEdit.listComponents(p, "packs/BP/manifest.json") }
    }

    @Test fun manualEditsAreCaughtByProjectDiagnostics() = withTempDir { d ->
        val p = setup(d.resolve("projects"))
        assertTrue(AddonWriter(p).writeEntity(golem()).ok)
        p.updateJson(ENTITY) { root ->
            val body = root.obj("minecraft:entity")!!
            root.with("minecraft:entity", body.with("components", body.obj("components")!!.with("minecraft:health", jsonObj("value" to js("muito")))))
        }
        val e = p.diagnose().errors.first { it.code == "PARAM_TYPE" }
        assertEquals("BP/entities/ruby_golem.json", e.file)
    }

    @Test fun scriptsAreLintedAgainstManifestDependencies() = withTempDir { d ->
        val p = setup(d.resolve("projects"))
        val r = AddonWriter(p).writeScript("scripts/main.js", "import { x } from \"@minecraft/server-ui\";\n")
        assertTrue(r.ok)
        assertTrue(r.diagnostics.any { it.code == "SCRIPT_MODULE_NOT_DECLARED" })
        assertTrue(Files.exists(p.resolve("packs/BP/scripts/main.js")))
        val bad = AddonWriter(p, overwrite = true).writeScript("../fora.js")
        assertFalse(bad.ok)
        assertFalse(AddonWriter(p).writeFunction("Nome Ruim", "say oi").ok)
        assertFalse(AddonWriter(p).writeLootTable("../x", LootTable(emptyList())).ok)
    }
}
