package studio.core

import studio.core.geometry.*
import studio.core.json.*
import studio.core.util.EditException
import kotlin.test.*

class GeometryTest {
    private val SRC = """
    {
      "format_version": "1.12.0",
      "x_unknown_root": {"keep": true},
      "minecraft:geometry": [
        {
          "description": {"identifier": "geometry.golem", "texture_width": 64, "texture_height": 64, "visible_bounds_width": 2, "visible_bounds_height": 3, "visible_bounds_offset": [0, 1.5, 0]},
          "bones": [
            {"name": "root", "pivot": [0, 0, 0]},
            {"name": "body", "parent": "root", "pivot": [0, 12, 0], "x_bone_extra": 7, "cubes": [
              {"origin": [-4, 12, -2], "size": [8, 12, 4], "uv": [16, 16], "x_cube_extra": "ok"}
            ]},
            {"name": "arm_l", "parent": "body", "pivot": [4, 22, 0], "rotation": [10, 0, 0], "cubes": [
              {"origin": [4, 12, -2], "size": [4, 12, 4], "uv": {"north": {"uv": [44, 20], "uv_size": [4, 12], "material_instance": "m"}, "up": {"uv": [44, 16]}}}
            ]}
          ]
        },
        {"description": {"identifier": "geometry.other"}, "bones": [{"name": "o", "pivot": [0, 0, 0]}], "x_geo_extra": 1}
      ]
    }
    """

    private fun load(): Pair<GeometryFile, Geometry> {
        val gf = GeometryFile.parse(SRC)
        return gf to gf.find("geometry.golem")!!.geometry!!
    }

    @Test fun parsesBonesCubesAndBothUvStyles() {
        val (_, g) = load()
        assertEquals(64, g.textureWidth)
        assertEquals(listOf("root", "body", "arm_l"), g.bones.map { it.name })
        assertEquals(Vec3(-4.0, 12.0, -2.0), g.bone("body")!!.cubes[0].origin)
        assertTrue(g.bone("body")!!.cubes[0].uv is CubeUv.Box)
        val pf = g.bone("arm_l")!!.cubes[0].uv as CubeUv.PerFace
        assertEquals(4.0, pf.faces[FaceName.NORTH]!!.w)
        assertNull(pf.faces[FaceName.UP]!!.w) // uv_size ausente continua ausente
    }

    @Test fun roundTripPreservesUnknownFieldsAndOtherGeometries() {
        val (gf, g) = load()
        val edited = GeometryOps.moveCube(g, "body", 0, Vec3(1.0, 0.0, 0.0))
        val text = gf.replace(edited).toText()
        val root = Json.parseObject(text)
        assertEquals(JsonBool(true), root.obj("x_unknown_root")!!["keep"])
        val geos = root.arr("minecraft:geometry")!!.items.map { it as JsonObj }
        assertEquals(JsonNum("1"), geos[1]["x_geo_extra"]) // outra geometria intacta
        val body = geos[0].arr("bones")!!.items[1] as JsonObj
        assertEquals(JsonNum("7"), body["x_bone_extra"])
        val cube = body.arr("cubes")!!.items[0] as JsonObj
        assertEquals(JsonStr("ok"), cube["x_cube_extra"])
        assertEquals("-3", (cube.arr("origin")!!.items[0] as JsonNum).raw)
        val desc = geos[0].obj("description")!!
        assertTrue(desc["visible_bounds_offset"] != null)
        // face por UV mantém material_instance e não inventa uv_size
        val face = ((geos[0].arr("bones")!!.items[2] as JsonObj).arr("cubes")!!.items[0] as JsonObj).obj("uv")!!
        assertEquals(JsonStr("m"), face.obj("north")!!["material_instance"])
        assertNull(face.obj("up")!!["uv_size"])
    }

    @Test fun unchangedGeometryRewritesToSameData() {
        val (gf, g) = load()
        val again = GeometryFile.parse(gf.replace(g).toText()).find("geometry.golem")!!.geometry!!
        assertEquals(g.bones.map { it.name }, again.bones.map { it.name })
        assertEquals(g.bones[2].cubes[0].uv, again.bones[2].cubes[0].uv)
        assertEquals(g.bones[1].cubes[0].origin, again.bones[1].cubes[0].origin)
    }

    @Test fun malformedGeometryIsReportedAndNotEditable() {
        val gf = GeometryFile.parse("""{"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.bad"},"bones":[{"pivot":[0,0,0]},{"name":"a","cubes":[{"origin":[1,2],"size":[1,1,1],"uv":[0,0]}]}]}]}""")
        val e = gf.entries().single()
        assertNull(e.geometry)
        assertTrue(e.diagnostics.any { it.code == "GEO_BONE_NAME" })
        assertTrue(e.diagnostics.any { it.code == "GEO_CUBE_ORIGIN" })
    }

    @Test fun hierarchyQueries() {
        val (_, g) = load()
        assertEquals(listOf("body", "arm_l"), g.descendants("root").map { it.name })
        assertEquals(listOf("body", "root"), g.ancestors("arm_l").map { it.name })
        assertEquals(listOf("root"), g.roots().map { it.name })
        assertFalse(g.inCycle("body"))
        val cyc = g.copy(bones = g.bones.map { if (it.name == "root") it.copy(parent = "arm_l") else it })
        assertTrue(cyc.inCycle("root"))
        assertEquals(3, cyc.orderedBones().size) // não entra em laço infinito
    }

    @Test fun boneOperations() {
        val (_, g) = load()
        val added = GeometryOps.addBone(g, "head", "body")
        assertEquals(Vec3(0.0, 12.0, 0.0), added.bone("head")!!.pivot) // herda o pivô do pai
        assertFailsWith<EditException> { GeometryOps.addBone(g, "BODY") } // sem diferenciar caixa
        assertFailsWith<EditException> { GeometryOps.addBone(g, "x", "nada") }
        val renamed = GeometryOps.renameBone(g, "body", "torso")
        assertEquals("torso", renamed.bone("arm_l")!!.parent)
        assertEquals("torso", renamed.bone("torso")!!.name)
        assertFailsWith<EditException> { GeometryOps.renameBone(g, "body", "root") }
        assertFailsWith<EditException> { GeometryOps.reparent(g, "body", "arm_l") } // ciclo
        assertFailsWith<EditException> { GeometryOps.reparent(g, "body", "body") }
        val re = GeometryOps.reparent(g, "arm_l", "root")
        assertEquals("root", re.bone("arm_l")!!.parent)
        // remover mantendo filhos
        val keep = GeometryOps.removeBone(g, "body", removeChildren = false)
        assertEquals("root", keep.bone("arm_l")!!.parent)
        assertNull(keep.bone("body"))
        val drop = GeometryOps.removeBone(g, "body", removeChildren = true)
        assertEquals(listOf("root"), drop.bones.map { it.name })
    }

    @Test fun moveBoneMovesPivotCubesAndChildren() {
        val (_, g) = load()
        val m = GeometryOps.moveBone(g, "body", Vec3(0.0, 2.0, 0.0))
        assertEquals(Vec3(0.0, 14.0, 0.0), m.bone("body")!!.pivot)
        assertEquals(Vec3(-4.0, 14.0, -2.0), m.bone("body")!!.cubes[0].origin)
        assertEquals(Vec3(4.0, 24.0, 0.0), m.bone("arm_l")!!.pivot)
        assertEquals(Vec3(0.0, 0.0, 0.0), m.bone("root")!!.pivot)
        val only = GeometryOps.moveBone(g, "body", Vec3(0.0, 2.0, 0.0), withChildren = false)
        assertEquals(Vec3(4.0, 22.0, 0.0), only.bone("arm_l")!!.pivot)
    }

    @Test fun cubeOperations() {
        val (_, g) = load()
        val a = GeometryOps.addCube(g, "root", Vec3(0.0, 0.0, 0.0), Vec3(2.0, 2.0, 2.0))
        val uv = a.bone("root")!!.cubes[0].uv as CubeUv.Box
        // não pode cair em cima do UV do corpo (16,16) nem do braço
        assertTrue(UvLayout.overlaps(a).isEmpty(), "novo cubo deve achar região livre")
        assertTrue(uv.u >= 0)
        assertFailsWith<EditException> { GeometryOps.removeCube(g, "root", 0) }
        assertFailsWith<EditException> { GeometryOps.addCube(g, "root", Vec3(Double.NaN, 0.0, 0.0), Vec3.ONE) }
        val d = GeometryOps.duplicateCube(g, "body", 0, Vec3(10.0, 0.0, 0.0))
        assertEquals(2, d.bone("body")!!.cubes.size)
        assertEquals(Vec3(6.0, 12.0, -2.0), d.bone("body")!!.cubes[1].origin)
        val mv = GeometryOps.moveCubeToBone(g, "body", 0, "root")
        assertEquals(0, mv.bone("body")!!.cubes.size)
        assertEquals(1, mv.bone("root")!!.cubes.size)
        val r = GeometryOps.resizeCube(g, "body", 0, Vec3(2.0, 2.0, 2.0))
        assertEquals(Vec3(2.0, 2.0, 2.0), r.bone("body")!!.cubes[0].size)
    }

    @Test fun mirrorSubtreeReflectsAcrossX() {
        val (_, g) = load()
        val m = GeometryOps.mirrorSubtree(g, "arm_l")
        val r = m.bone("arm_r")!!
        assertEquals("body", r.parent)
        assertEquals(Vec3(-4.0, 22.0, 0.0), r.pivot)
        assertEquals(Vec3(10.0, 0.0, 0.0), r.rotation) // y e z trocam de sinal (0 continua 0)
        val c = r.cubes[0]
        assertEquals(-8.0, c.origin.x) // 2*0 - (4+4)
        assertEquals(true, r.mirror)
        val pf = c.uv as CubeUv.PerFace
        assertEquals(-4.0, pf.faces[FaceName.NORTH]!!.w) // largura invertida = espelhado
        assertFailsWith<EditException> { GeometryOps.mirrorSubtree(g, "arm_l", nameOf = { it }) }
        assertFailsWith<EditException> { GeometryOps.mirrorSubtree(m, "arm_l") } // arm_r já existe
    }

    @Test fun editorUndoRedoAndFailureLeavesStateAlone() {
        val (_, g) = load()
        val ed = GeometryEditor(g)
        ed.addBone("head", "body")
        ed.renameBone("head", "skull")
        assertEquals("Renomear osso", ed.undoLabel)
        assertFailsWith<EditException> { ed.renameBone("skull", "root") }
        assertEquals(4, ed.geometry.bones.size)
        assertTrue(ed.undo())
        assertNotNull(ed.geometry.bone("head"))
        assertTrue(ed.undo())
        assertNull(ed.geometry.bone("head"))
        assertFalse(ed.undo())
        assertTrue(ed.redo())
        assertTrue(ed.canRedo)
        ed.addBone("x") // nova edição limpa o refazer
        assertFalse(ed.canRedo)
    }

    @Test fun textureSizeChangeCanScaleUv() {
        val (_, g) = load()
        val s = GeometryOps.setTextureSize(g, 128, 128, scaleUv = true)
        assertEquals(CubeUv.Box(32.0, 32.0), s.bone("body")!!.cubes[0].uv)
        val keep = GeometryOps.setTextureSize(g, 128, 128, scaleUv = false)
        assertEquals(CubeUv.Box(16.0, 16.0), keep.bone("body")!!.cubes[0].uv)
        assertFailsWith<EditException> { GeometryOps.setTextureSize(g, 0, 10, false) }
    }

    @Test fun legacyFormatConvertsAndKeepsData() {
        val legacy = """{"format_version":"1.8.0","geometry.old:geometry.base":{"texturewidth":64,"textureheight":32,"bones":[{"name":"head","pivot":[0,24,0],"bind_pose_rotation":[0,0,0],"cubes":[{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0]}]}]}}"""
        val gf = GeometryFile.parse(legacy)
        val e = gf.entries().single()
        assertEquals("geometry.old", e.identifier)
        assertEquals(1, e.geometry!!.cubeCount)
        assertTrue(e.diagnostics.any { it.code == "GEO_LEGACY_INHERIT" })
        assertTrue(e.diagnostics.any { it.code == "GEO_LEGACY_DROPPED" })
        assertFailsWith<GeometryFormatException> { gf.replace(e.geometry!!) } // precisa converter antes
        val (modern, _) = gf.toModern()
        val round = GeometryFile.parse(modern.toText())
        assertEquals(64, round.find("geometry.old")!!.geometry!!.textureWidth)
        assertEquals("1.12.0", round.formatVersion)
        assertNull(round.find("geometry.old")!!.geometry!!.bones[0].raw["bind_pose_rotation"])
    }

    @Test fun fileLevelAddRemoveAndDuplicates() {
        val (gf, g) = load()
        assertFailsWith<GeometryFormatException> { gf.add(g) }
        val copy = gf.add(g.copy(identifier = "geometry.copy"))
        assertEquals(3, copy.identifiers().size)
        assertEquals(2, copy.remove("geometry.copy").identifiers().size)
        assertFailsWith<GeometryFormatException> { gf.replace(g.copy(identifier = "geometry.other"), "geometry.golem") }
        assertFailsWith<GeometryFormatException> { GeometryFile.parse("{\"a\":1}") }
        assertFailsWith<GeometryFormatException> { GeometryFile.parse("{ nope") }
    }
}
