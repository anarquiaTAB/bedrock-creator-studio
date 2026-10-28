package studio.core

import studio.core.geometry.*
import studio.core.image.Bitmap
import studio.core.render.SoftRenderer
import studio.core.util.EditException
import kotlin.math.abs
import kotlin.test.*

class UvRenderTest {
    private fun geo(vararg cubes: Triple<String, Vec3, Vec3>, tw: Int = 64, th: Int = 64): Geometry {
        val bones = cubes.map { (n, o, s) -> Bone(n, null, Vec3.ZERO, cubes = listOf(Cube(o, s, CubeUv.Box(0.0, 0.0)))) }
        return Geometry("geometry.t", tw, th, bones)
    }

    @Test fun boxUnwrapLayoutAndFootprint() {
        val f = BoxUv.faces(Vec3(8.0, 12.0, 4.0), 16.0, 16.0, false)
        assertEquals(UvRect(20.0, 16.0, 8.0, 4.0), f[FaceName.UP])
        assertEquals(UvRect(20.0, 20.0, 8.0, 12.0), f[FaceName.NORTH])
        assertEquals(UvRect(16.0, 20.0, 4.0, 12.0), f[FaceName.EAST])
        assertEquals(UvRect(28.0, 20.0, 4.0, 12.0), f[FaceName.WEST])
        assertEquals(UvRect(32.0, 20.0, 8.0, 12.0), f[FaceName.SOUTH])
        assertEquals(24 to 16, BoxUv.footprint(Vec3(8.0, 12.0, 4.0)))
        val m = BoxUv.faces(Vec3(8.0, 12.0, 4.0), 16.0, 16.0, true)
        assertEquals(f[FaceName.WEST]!!.copy(flipU = true), m[FaceName.EAST]) // espelhado troca leste/oeste e inverte
        assertTrue(m.values.all { it.flipU })
    }

    @Test fun overlapDetectionIgnoresIntentionalSharing() {
        val a = Cube(Vec3.ZERO, Vec3(4.0, 4.0, 4.0), CubeUv.Box(0.0, 0.0))
        val sharedMirror = a.copy(origin = Vec3(10.0, 0.0, 0.0), mirror = true)
        val g1 = Geometry("geometry.t", 64, 64, listOf(Bone("a", null, Vec3.ZERO, cubes = listOf(a, sharedMirror))))
        assertTrue(UvLayout.overlaps(g1).isEmpty())
        val partial = a.copy(uv = CubeUv.Box(4.0, 0.0))
        val g2 = Geometry("geometry.t", 64, 64, listOf(Bone("a", null, Vec3.ZERO, cubes = listOf(a, partial))))
        assertEquals(1, UvLayout.overlaps(g2).size)
    }

    @Test fun autoLayoutRemovesOverlapAndKeepsSharedGroups() {
        val c = Cube(Vec3.ZERO, Vec3(8.0, 8.0, 8.0), CubeUv.Box(0.0, 0.0))
        val big = c.copy(size = Vec3(16.0, 16.0, 8.0))
        val g = Geometry("geometry.t", 64, 64, listOf(Bone("a", null, Vec3.ZERO, cubes = listOf(c, c.copy(mirror = true), big, c.copy(size = Vec3(2.0, 2.0, 2.0))))))
        val r = UvLayout.autoLayout(g)
        assertFalse(r.textureChanged)
        assertTrue(UvLayout.overlaps(r.geometry).isEmpty())
        val cubes = r.geometry.bones[0].cubes
        assertEquals(cubes[0].uv, cubes[1].uv) // grupo compartilhado continua junto
        // tudo dentro da textura
        assertTrue(ModelChecksAccess.noBounds(r.geometry))
    }

    @Test fun autoLayoutFailsOrGrows() {
        val big = Cube(Vec3.ZERO, Vec3(30.0, 30.0, 30.0), CubeUv.Box(0.0, 0.0)) // 120 x 60
        val g = Geometry("geometry.t", 64, 64, listOf(Bone("a", null, Vec3.ZERO, cubes = listOf(big))))
        assertFailsWith<EditException> { UvLayout.autoLayout(g) }
        val r = UvLayout.autoLayout(g, grow = true)
        assertTrue(r.textureChanged)
        assertTrue(r.geometry.textureWidth!! >= 120 && r.geometry.textureHeight!! >= 60)
    }

    @Test fun remapTextureMovesPixelsWithTheCube() {
        val a = Cube(Vec3.ZERO, Vec3(2.0, 2.0, 2.0), CubeUv.Box(0.0, 0.0))
        val b = Cube(Vec3.ZERO, Vec3(2.0, 2.0, 2.0), CubeUv.Box(0.0, 0.0)).copy(size = Vec3(2.0, 2.0, 3.0))
        val before = Geometry("geometry.t", 32, 32, listOf(Bone("a", null, Vec3.ZERO, cubes = listOf(a.copy(uv = CubeUv.Box(10.0, 10.0)), b.copy(uv = CubeUv.Box(20.0, 20.0))))))
        val tex = Bitmap(32, 32)
        tex[10 + 2, 10 + 2] = 0xFFFF0000.toInt() // dentro da face do cubo 0
        tex[20 + 3, 20 + 3] = 0xFF00FF00.toInt()
        val after = UvLayout.autoLayout(before).geometry
        val out = UvLayout.remapTexture(before, after, tex)
        val u0 = (after.bones[0].cubes[0].uv as CubeUv.Box)
        val u1 = (after.bones[0].cubes[1].uv as CubeUv.Box)
        assertEquals(0xFFFF0000.toInt(), out[u0.u.toInt() + 2, u0.v.toInt() + 2])
        assertEquals(0xFF00FF00.toInt(), out[u1.u.toInt() + 3, u1.v.toInt() + 3])
    }

    @Test fun textureMaskAndOwners() {
        val g = geo(Triple("a", Vec3.ZERO, Vec3(2.0, 2.0, 2.0)))
        val m = UvLayout.textureMask(g)
        assertTrue(m.contains(2, 0)) // topo: (u+d, v)
        assertFalse(m.contains(40, 40))
        assertTrue(UvLayout.owners(g, 2, 0).any { it.second == FaceName.UP })
    }

    @Test fun matricesInverseAndRotationOrder() {
        val m = Mat4.translation(Vec3(1.0, 2.0, 3.0)) * Mat4.rotY(90.0) * Mat4.scale(Vec3(2.0, 2.0, 2.0))
        val p = m.transformPoint(Vec3(1.0, 0.0, 0.0))
        assertTrue(p.near(Vec3(1.0, 2.0, 1.0)), "got $p") // (2,0,0) -> rotY90 -> (0,0,-2) -> +t
        assertTrue(m.inverse()!!.transformPoint(p).near(Vec3(1.0, 0.0, 0.0)))
        assertNull(Mat4.scale(Vec3(0.0, 1.0, 1.0)).inverse())
    }

    @Test fun boneRotationPivotsAroundPivot() {
        val g = Geometry("geometry.t", 16, 16, listOf(
            Bone("arm", null, Vec3(0.0, 10.0, 0.0), cubes = listOf(Cube(Vec3(-1.0, 0.0, -1.0), Vec3(2.0, 10.0, 2.0), CubeUv.Box(0.0, 0.0)))),
        ))
        // sem convenção de sinal (negateRotX=false) rotX(90) leva a ponta de baixo (y=0) para o lado de z
        val conv = PoseConvention(false, false, false)
        val cubes = Posing.compute(g, mapOf("arm" to BonePose(rotation = Vec3(90.0, 0.0, 0.0))), conv)
        val b = Posing.bounds(cubes)!!
        assertTrue(abs(b.max.y - 11.0) < 1e-6 || abs(b.min.y - 9.0) < 1e-6, "pivô em y=10 deve ficar fixo: $b")
        assertTrue(abs(b.min.z) > 5 || abs(b.max.z) > 5)
        // convenção padrão inverte o sentido
        val d = Posing.bounds(Posing.compute(g, mapOf("arm" to BonePose(rotation = Vec3(90.0, 0.0, 0.0)))))!!
        assertTrue(abs(b.max.z + d.min.z) < 1e-6 || abs(b.min.z + d.max.z) < 1e-6)
    }

    @Test fun childFollowsParentAndScaleAndPosition() {
        val g = Geometry("geometry.t", 16, 16, listOf(
            Bone("p", null, Vec3.ZERO),
            Bone("c", "p", Vec3(0.0, 5.0, 0.0), cubes = listOf(Cube(Vec3(0.0, 5.0, 0.0), Vec3.ONE, CubeUv.Box(0.0, 0.0)))),
        ))
        val moved = Posing.compute(g, mapOf("p" to BonePose(position = Vec3(0.0, 3.0, 0.0))))
        assertTrue(Posing.bounds(moved)!!.min.y.let { abs(it - 8.0) < 1e-6 })
        val scaled = Posing.compute(g, mapOf("c" to BonePose(scale = Vec3(2.0, 2.0, 2.0))))
        assertTrue(abs(Posing.bounds(scaled)!!.size.x - 2.0) < 1e-6)
    }

    @Test fun cameraProjectionAndPickingAgree() {
        val g = geo(Triple("a", Vec3(-4.0, 0.0, -4.0), Vec3(8.0, 8.0, 8.0)), Triple("far", Vec3(-4.0, 0.0, 20.0), Vec3(8.0, 8.0, 8.0)))
        val cubes = Posing.compute(g)
        val cam = OrbitCamera(Vec3(0.0, 4.0, 0.0), 0.0, 0.0, 60.0)
        val s = cam.project(Vec3(0.0, 4.0, 0.0), 200, 200)!!
        assertTrue(abs(s.x - 100) < 1e-6 && abs(s.y - 100) < 1e-6)
        val hit = Posing.pick(cubes, cam.ray(100.0, 100.0, 200, 200))!!
        assertEquals("a", hit.bone)
        assertEquals(FaceName.NORTH, hit.face) // câmera em yaw 0 olha a face norte
        assertNull(Posing.pick(cubes, cam.ray(5.0, 5.0, 200, 200)))
        // projeção de um ponto e raio de volta para o mesmo pixel
        val p = Vec3(-3.0, 6.0, -4.0)
        val sp = cam.project(p, 300, 300)!!
        val ray = cam.ray(sp.x, sp.y, 300, 300)
        val t = (p - ray.origin).dot(ray.dir)
        assertTrue((ray.origin + ray.dir * t).near(p, 1e-6))
        // dos lados: yaw 90 olha a partir de +X
        cam.yaw = 90.0
        assertEquals(FaceName.EAST, Posing.pick(cubes, cam.ray(100.0, 100.0, 200, 200))!!.face)
    }

    @Test fun cameraOrbitZoomPanFrame() {
        val cam = OrbitCamera()
        cam.orbit(100.0, 1000.0)
        assertEquals(89.0, cam.pitch)
        cam.zoom(0.0001)
        assertEquals(0.5, cam.distance)
        assertFailsWith<IllegalArgumentException> { cam.zoom(-1.0) }
        val before = cam.target
        cam.pan(10.0, 0.0, 400)
        assertFalse(cam.target.near(before))
        cam.frame(Aabb(Vec3(-10.0, 0.0, -10.0), Vec3(10.0, 20.0, 10.0)))
        assertTrue(cam.target.near(Vec3(0.0, 10.0, 0.0)))
        assertTrue(cam.project(Vec3(10.0, 20.0, 10.0), 400, 400) != null)
    }

    @Test fun rendererPaintsRealTextureOnTheFaces() {
        // cubo 8x8x8; textura 64x64 com cada região de face numa cor distinta
        val g = geo(Triple("a", Vec3(-4.0, 0.0, -4.0), Vec3(8.0, 8.0, 8.0)))
        val tex = Bitmap(64, 64)
        val rects = BoxUv.faces(Vec3(8.0, 8.0, 8.0), 0.0, 0.0, false)
        val colors = mapOf(
            FaceName.NORTH to 0xFFFF0000.toInt(), FaceName.SOUTH to 0xFF00FF00.toInt(), FaceName.EAST to 0xFF0000FF.toInt(),
            FaceName.WEST to 0xFFFFFF00.toInt(), FaceName.UP to 0xFFFF00FF.toInt(), FaceName.DOWN to 0xFF00FFFF.toInt(),
        )
        for ((f, r) in rects) for (y in r.y.toInt() until (r.y + r.h).toInt()) for (x in r.x.toInt() until (r.x + r.w).toInt()) tex[x, y] = colors.getValue(f)
        val cam = OrbitCamera(Vec3(0.0, 4.0, 0.0), 0.0, 0.0, 40.0)
        val img = SoftRenderer.render(g, emptyMap(), tex, cam, SoftRenderer.Options(100, 100, background = 0xFF202020.toInt()))
        val c = img[50, 50]
        // frente (norte) vermelha, sombreada a 80%
        assertTrue(abs(((c shr 16) and 255) - 204) <= 2 && ((c shr 8) and 255) < 5 && (c and 255) < 5, "centro = ${Integer.toHexString(c)}")
        assertEquals(0xFF202020.toInt(), img[2, 2]) // fundo
        // de cima: vê a face UP
        val top = SoftRenderer.render(g, emptyMap(), tex, OrbitCamera(Vec3(0.0, 4.0, 0.0), 0.0, 89.0, 40.0), SoftRenderer.Options(100, 100))
        val t = top[50, 50]
        assertTrue(((t shr 16) and 255) > 200 && (t and 255) > 200 && ((t shr 8) and 255) < 5, "topo = ${Integer.toHexString(t)}")
        // pelo lado +X vê EAST (azul)
        val east = SoftRenderer.render(g, emptyMap(), tex, OrbitCamera(Vec3(0.0, 4.0, 0.0), 90.0, 0.0, 40.0), SoftRenderer.Options(100, 100))
        assertTrue((east[50, 50] and 255) > 150 && ((east[50, 50] shr 16) and 255) < 5)
    }

    @Test fun rendererRespectsTransparencyDepthAndHighlight() {
        val g = geo(Triple("front", Vec3(-4.0, 0.0, -10.0), Vec3(8.0, 8.0, 2.0)), Triple("back", Vec3(-4.0, 0.0, 0.0), Vec3(8.0, 8.0, 2.0)))
        val g2 = g.copy(bones = listOf(
            g.bones[0], g.bones[1].copy(cubes = listOf(g.bones[1].cubes[0].copy(uv = CubeUv.Box(32.0, 0.0)))),
        ))
        val tex = Bitmap(64, 64)
        for (y in 0 until 64) for (x in 0 until 64) tex[x, y] = if (x < 32) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        // frente: parte da textura transparente na face norte
        val cam = OrbitCamera(Vec3(0.0, 4.0, 0.0), 0.0, 0.0, 60.0)
        val img = SoftRenderer.render(g2, emptyMap(), tex, cam, SoftRenderer.Options(100, 100))
        assertTrue(((img[50, 50] shr 16) and 255) > 100, "o cubo da frente (vermelho) encobre o de trás")
        for (y in 0 until 64) for (x in 0 until 32) tex[x, y] = 0 // frente transparente
        val img2 = SoftRenderer.render(g2, emptyMap(), tex, cam, SoftRenderer.Options(100, 100))
        assertTrue((img2[50, 50] and 255) > 100, "recorte alfa deixa ver o cubo de trás (azul)")
        val hl = SoftRenderer.render(g2, emptyMap(), null, cam, SoftRenderer.Options(100, 100, highlight = setOf(CubeRef("front", 0))))
        val plain = SoftRenderer.render(g2, emptyMap(), null, cam, SoftRenderer.Options(100, 100))
        assertNotEquals(hl[50, 50], plain[50, 50])
    }
}

private object ModelChecksAccess {
    fun noBounds(g: Geometry): Boolean = studio.core.diagnostics.ModelChecks.checkGeometry(g, null).none { it.code == "GEO_UV_BOUNDS" }
}
