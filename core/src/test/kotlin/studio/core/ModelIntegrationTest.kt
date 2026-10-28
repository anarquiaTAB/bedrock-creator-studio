package studio.core

import studio.core.addon.*
import studio.core.animation.*
import studio.core.diagnostics.*
import studio.core.geometry.*
import studio.core.json.*
import studio.core.manifest.*
import studio.core.pack.Packager
import studio.core.project.Project
import studio.core.project.ProjectStore
import studio.core.util.EditException
import kotlin.test.*

class ControllerTest {
    private val SRC = """
    {"format_version":"1.10.0","x_root":1,"animation_controllers":{
      "controller.animation.golem.move":{"initial_state":"idle","x_ctrl":"keep","states":{
        "idle":{"animations":["idle"],"transitions":[{"walking":"query.is_moving"}],"blend_transition":0.2,"x_state":1},
        "walking":{"animations":[{"walk":"query.ground_speed * 0.5"}],"transitions":[{"idle":"!query.is_moving"}],"blend_transition":{"0.0":0.0,"0.2":1.0}}
      }}}}
    """

    private fun load() = ControllerFile.parse(SRC).find("controller.animation.golem.move")!!.controller!!

    @Test fun parsesAndRoundTripsWithoutLosingFields() {
        val f = ControllerFile.parse(SRC)
        val c = f.find("controller.animation.golem.move")!!.controller!!
        assertEquals("idle", c.initialState)
        assertEquals(listOf(StateAnim("walk", "query.ground_speed * 0.5")), c.state("walking")!!.animations)
        assertEquals(0.2, c.state("idle")!!.blendTransition)
        assertNull(c.state("walking")!!.blendTransition) // forma complexa: não numérica
        val back = Json.parseObject(f.replace(ControllerOps.addState(c, "jump")).toText())
        assertEquals(JsonNum("1"), back["x_root"])
        val ctrl = back.obj("animation_controllers")!!.obj("controller.animation.golem.move")!!
        assertEquals(JsonStr("keep"), ctrl["x_ctrl"])
        assertEquals(JsonNum("1"), ctrl.obj("states")!!.obj("idle")!!["x_state"])
        assertTrue(ctrl.obj("states")!!.obj("walking")!!["blend_transition"] is JsonObj) // preservada
        assertTrue(ctrl.obj("states")!!.obj("jump") != null)
    }

    @Test fun stateOperationsKeepReferencesConsistent() {
        val c = load()
        val r = ControllerOps.renameState(c, "idle", "parado")
        assertEquals("parado", r.initialState)
        assertEquals("parado", r.state("walking")!!.transitions[0].target)
        val rm = ControllerOps.removeState(c, "walking")
        assertTrue(rm.state("idle")!!.transitions.isEmpty())
        val rmInitial = ControllerOps.removeState(c, "idle")
        assertEquals("walking", rmInitial.initialState)
        assertFailsWith<EditException> { ControllerOps.addState(c, "idle") }
        assertFailsWith<EditException> { ControllerOps.addTransition(c, "idle", "nada", "1") }
        assertFailsWith<EditException> { ControllerOps.addTransition(c, "idle", "walking", "1 +") }
        assertFailsWith<EditException> { ControllerOps.setBlendTransition(c, "idle", -1.0) }
        val ed = ControllerEditor(c)
        ed.addTransition("idle", "walking", "query.is_jumping")
        assertEquals(2, ed.controller.state("idle")!!.transitions.size)
        ed.setBlendTransition("idle", null)
        assertTrue(ed.undo() && ed.undo())
        assertEquals(1, ed.controller.state("idle")!!.transitions.size)
        // removendo o blend numérico do arquivo
        val noBlend = ControllerFile.parse(SRC).replace(ControllerOps.setBlendTransition(c, "idle", null)).toText()
        assertNull(Json.parseObject(noBlend).obj("animation_controllers")!!.obj("controller.animation.golem.move")!!.obj("states")!!.obj("idle")!!["blend_transition"])
    }

    @Test fun checkerFindsBrokenControllers() {
        val bad = Json.parseObject("""{"format_version":"1.10.0","animation_controllers":{"controller.animation.a":{"initial_state":"x","states":{"default":{"transitions":[{"ghost":"1"},{"default":"q.a +"}]},"orphan":{}}}}}""")
        val d = ModelChecks.checkControllerFile(bad, "f.json")
        assertTrue(d.any { it.code == "CTRL_INITIAL_MISSING" && it.severity == Severity.ERROR })
        assertTrue(d.any { it.code == "CTRL_TARGET_MISSING" })
        assertTrue(d.any { it.code == "MOLANG_SYNTAX" && it.category == Category.SYNTAX })
        assertTrue(ModelChecks.checkControllerFile(Json.parseObject(SRC), null).none { it.severity != Severity.INFO })
    }
}

class ModelIntegrationTest {
    private fun setup(base: java.nio.file.Path): Project {
        val p = ProjectStore(base).create("Ruby", "1.21.0")
        val rp = ManifestSpec(PackKind.RESOURCES, "Ruby RP", minEngineVersion = listOf(1, 21, 0))
        p.writeText("packs/RP/manifest.json", Json.write(ManifestBuilder.build(rp)))
        return p
    }

    private val GEO = """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.golem","texture_width":32,"texture_height":32},"bones":[
        {"name":"body","pivot":[0,12,0],"cubes":[{"origin":[-4,12,-2],"size":[8,12,4],"uv":[0,0]}]},
        {"name":"arm_l","parent":"body","pivot":[4,22,0],"cubes":[{"origin":[4,12,-2],"size":[4,12,4],"uv":[0,16]}]}]}]}"""
    private val ANIM = """{"format_version":"1.8.0","animations":{"animation.golem.walk":{"loop":true,"animation_length":1,"bones":{"arm_l":{"rotation":{"0.0":[0,0,0],"0.5":[30,0,0],"1.0":[0,0,0]}}}}}}"""
    private val ENTITY = """{"format_version":"1.10.0","minecraft:client_entity":{"x_keep":true,"description":{"identifier":"studio:golem","textures":{"default":"textures/entity/golem"},"geometry":{"default":"geometry.golem"},"materials":{"default":"entity_alphatest"},"render_controllers":["controller.render.golem"]}}}"""

    private fun full(d: java.nio.file.Path): Project {
        val p = setup(d.resolve("projects"))
        p.writeText("packs/RP/models/entity/golem.geo.json", GEO)
        p.writeText("packs/RP/animations/golem.animation.json", ANIM)
        p.writeText("packs/RP/entity/golem.entity.json", ENTITY)
        Samples.writePng(p.resolve("packs/RP/textures/entity/golem.png"))
        return p
    }

    @Test fun storeListsLoadsAndSavesOnlyTheEditedItem() = withTempDir { d ->
        val p = full(d)
        val s = ModelStore(p)
        val g = s.listGeometries().single()
        assertEquals("geometry.golem", g.identifier)
        assertTrue(g.editable && !g.legacy)
        val geo = s.loadGeometry(g.file, g.identifier)
        s.saveGeometry(g.file, GeometryOps.addBone(geo, "head", "body"))
        assertEquals(3, s.loadGeometry(g.file, g.identifier).bones.size)
        assertTrue(p.readText(g.file).contains("\"head\""))
        // animações
        val a = s.listAnimations().single()
        val anim = AnimationOps.setLength(s.loadAnimation(a.file, a.name), 2.0)
        s.saveAnimation(a.file, anim)
        assertEquals(2.0, s.loadAnimation(a.file, a.name).length)
        s.createAnimation("packs/RP/animations/golem.extra.animation.json", Animation("animation.golem.idle", Loop.LOOP, 1.0))
        assertEquals(2, s.listAnimations().size)
        assertFailsWith<GeometryFormatException> { s.createGeometry("packs/RP/models/entity/golem.geo.json", geo) } // mesmo identificador
        s.createGeometry("packs/RP/models/entity/other.geo.json", geo.copy(identifier = "geometry.other"))
        assertEquals(2, s.listGeometries().size)
        // controlador novo
        val c = Controller("controller.animation.golem.move", "idle", listOf(ControllerState("idle", listOf(StateAnim("idle")))))
        s.createController("packs/RP/animation_controllers/golem.ac.json", c)
        assertEquals("idle", s.loadController("packs/RP/animation_controllers/golem.ac.json", c.name).initialState)
    }

    @Test fun invalidFilesAreNotOpenedAndNotTouched() = withTempDir { d ->
        val p = setup(d.resolve("projects"))
        p.writeText("packs/RP/models/entity/bad.geo.json", """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.bad"},"bones":[{"pivot":[0,0,0]}]}]}""")
        val s = ModelStore(p)
        val ref = s.listGeometries().single()
        assertFalse(ref.editable)
        assertFailsWith<studio.core.storage.StorageException> { s.loadGeometry(ref.file, ref.identifier) }
        p.writeText("packs/RP/models/entity/broken.geo.json", "{ nope")
        assertEquals(1, s.listGeometries().size) // arquivo ilegível é ignorado na lista, não derruba a tela
        assertEquals("{ nope", p.readText("packs/RP/models/entity/broken.geo.json"))
    }

    @Test fun legacyFileIsConvertedOnRequest() = withTempDir { d ->
        val p = setup(d.resolve("projects"))
        p.writeText("packs/RP/models/entity/old.json", """{"geometry.old":{"texturewidth":16,"textureheight":16,"bones":[{"name":"a","pivot":[0,0,0],"cubes":[{"origin":[0,0,0],"size":[1,1,1],"uv":[0,0]}]}]}}""")
        val s = ModelStore(p)
        assertTrue(s.listGeometries().single().legacy)
        s.convertLegacyGeometryFile("packs/RP/models/entity/old.json")
        val ref = s.listGeometries().single()
        assertFalse(ref.legacy)
        assertEquals(1, s.loadGeometry(ref.file, ref.identifier).cubeCount)
        assertEquals("1.12.0", Json.parseObject(p.readText(ref.file)).str("format_version"))
    }

    @Test fun attachAndDetachAnimationEditTheRealEntityFile() = withTempDir { d ->
        val p = full(d)
        val s = ModelStore(p)
        val f = "packs/RP/entity/golem.entity.json"
        s.attachAnimation(f, "walk", "animation.golem.walk", condition = "query.is_moving")
        val links = s.entityLinks(f)
        assertEquals("animation.golem.walk", links.animations["walk"])
        assertEquals(listOf("walk"), links.animate)
        val root = Json.parseObject(p.readText(f))
        assertEquals(JsonBool(true), root.obj("minecraft:client_entity")!!["x_keep"]) // nada se perdeu
        assertTrue(root.obj("minecraft:client_entity")!!.obj("description")!!.obj("textures") != null)
        assertFailsWith<EditException> { s.attachAnimation(f, "walk", "animation.golem.walk") }
        assertFailsWith<EditException> { s.attachAnimation(f, "run", "animation.golem.walk", condition = "1 +") }
        assertFalse(p.readText(f).contains("\"run\""))
        s.detachAnimation(f, "walk")
        assertTrue(s.entityLinks(f).animations.isEmpty() && s.entityLinks(f).animate.isEmpty())
        assertFailsWith<EditException> { s.detachAnimation(f, "walk") }
    }

    @Test fun renameBoneEverywhereUpdatesGeometryAndUsedAnimationsAtomically() = withTempDir { d ->
        val p = full(d)
        val s = ModelStore(p)
        val f = "packs/RP/entity/golem.entity.json"
        s.attachAnimation(f, "walk", "animation.golem.walk")
        // uma animação de OUTRA entidade com o mesmo nome de osso não deve ser tocada
        p.writeText("packs/RP/animations/unrelated.animation.json", """{"format_version":"1.8.0","animations":{"animation.other.wave":{"bones":{"arm_l":{"rotation":[1,2,3]}}}}}""")
        val changed = s.renameBoneEverywhere("packs/RP/models/entity/golem.geo.json", "geometry.golem", "arm_l", "arm_left")
        assertEquals(2, changed.size)
        assertTrue(s.loadGeometry("packs/RP/models/entity/golem.geo.json", "geometry.golem").hasBone("arm_left"))
        assertTrue("arm_left" in s.loadAnimation("packs/RP/animations/golem.animation.json", "animation.golem.walk").bones)
        assertTrue("arm_l" in s.loadAnimation("packs/RP/animations/unrelated.animation.json", "animation.other.wave").bones)
        // colisão: nada é gravado
        val before = p.readText("packs/RP/models/entity/golem.geo.json")
        assertFailsWith<EditException> { s.renameBoneEverywhere("packs/RP/models/entity/golem.geo.json", "geometry.golem", "arm_left", "body") }
        assertEquals(before, p.readText("packs/RP/models/entity/golem.geo.json"))
    }

    @Test fun diagnosticsCatchModelAnimationAndReferenceProblems() = withTempDir { d ->
        val p = full(d)
        val s = ModelStore(p)
        val f = "packs/RP/entity/golem.entity.json"
        s.attachAnimation(f, "walk", "animation.golem.walk")
        var r = p.diagnose()
        assertFalse(r.hasErrors, r.errors.joinToString())
        assertTrue(r.warnings.none { it.code.startsWith("REF_ANIM") || it.code.startsWith("ANIM_") || it.code.startsWith("GEO_") }, r.warnings.joinToString())

        // animação mexe em osso inexistente, referência quebrada e Molang inválido
        p.writeText("packs/RP/animations/golem.animation.json",
            """{"format_version":"1.8.0","animations":{"animation.golem.walk":{"animation_length":1,"bones":{"arm_x":{"rotation":{"0.0":[0,0,0],"2.0":["1 +",0,0]}}}}}}""")
        s.attachAnimation(f, "ghost", "animation.golem.nope")
        r = p.diagnose()
        assertTrue(r.warnings.any { it.code == "ANIM_BONE_MISSING" && it.message.contains("arm_x") })
        assertTrue(r.warnings.any { it.code == "ANIM_KEY_AFTER_END" })
        assertTrue(r.warnings.any { it.code == "REF_ANIMATION_MISSING" && it.message.contains("animation.golem.nope") })
        assertTrue(r.errors.any { it.code == "MOLANG_SYNTAX" && it.category == Category.SYNTAX })

        // geometria quebrada: pai inexistente, osso repetido, ciclo e UV fora da textura
        p.writeText("packs/RP/models/entity/golem.geo.json", """{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.golem","texture_width":16,"texture_height":16},"bones":[
            {"name":"a","parent":"b","pivot":[0,0,0],"cubes":[{"origin":[0,0,0],"size":[8,8,8],"uv":[0,0]}]},
            {"name":"b","parent":"a","pivot":[0,0,0]},
            {"name":"B","pivot":[0,0,0]},
            {"name":"c","parent":"zzz","pivot":[0,0,0]}]}]}""")
        r = p.diagnose()
        val codes = r.errors.map { it.code }.toSet()
        assertTrue(codes.containsAll(setOf("GEO_BONE_DUPLICATE", "GEO_CYCLE", "GEO_PARENT_MISSING")), codes.toString())
        assertTrue(r.warnings.any { it.code == "GEO_UV_BOUNDS" })
        // erros bloqueiam a exportação
        assertNull(Packager.exportMcpack(p.packDirs().single { it.first == "RP" }.second, d.resolve("x.mcpack")).output)
    }

    @Test fun controllerReferencesAreCheckedAgainstTheEntity() = withTempDir { d ->
        val p = full(d)
        val s = ModelStore(p)
        s.createController("packs/RP/animation_controllers/golem.ac.json", Controller("controller.animation.golem.move", "idle",
            listOf(ControllerState("idle", listOf(StateAnim("walk"), StateAnim("sumiu"))))))
        val f = "packs/RP/entity/golem.entity.json"
        s.attachAnimation(f, "move", "controller.animation.golem.move")
        s.attachAnimation(f, "walk", "animation.golem.walk", animate = false)
        val r = p.diagnose()
        assertFalse(r.hasErrors, r.errors.joinToString())
        assertTrue(r.warnings.any { it.code == "REF_CONTROLLER_ANIM_UNDEFINED" && it.message.contains("sumiu") })
        assertTrue(r.warnings.none { it.code == "REF_CONTROLLER_ANIM_UNDEFINED" && it.message.contains("'walk'") })
    }

    @Test fun editedModelExportsAsMcaddonAndKeepsContentsVerified() = withTempDir { d ->
        val p = full(d)
        val s = ModelStore(p)
        s.attachAnimation("packs/RP/entity/golem.entity.json", "walk", "animation.golem.walk")
        val g = s.loadGeometry("packs/RP/models/entity/golem.geo.json", "geometry.golem")
        s.saveGeometry("packs/RP/models/entity/golem.geo.json", GeometryOps.moveBone(g, "arm_l", Vec3(0.0, 1.0, 0.0)))
        val res = Packager.exportMcpack(p.packDirs().single().second, d.resolve("golem.mcpack"))
        assertNotNull(res.output, res.report.errors.joinToString())
        val insp = Packager.inspect(res.output!!)
        assertFalse(insp.diagnostics.any { it.severity == Severity.ERROR })
        // o arquivo dentro do pacote é a geometria editada
        val ex = d.resolve("ex")
        studio.core.archive.SafeZip.extract(res.output!!, ex)
        val text = String(java.nio.file.Files.readAllBytes(ex.resolve("models/entity/golem.geo.json")))
        assertTrue(GeometryFile.parse(text).find("geometry.golem")!!.geometry!!.bone("arm_l")!!.pivot == Vec3(4.0, 23.0, 0.0))
    }
}
