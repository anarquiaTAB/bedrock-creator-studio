package studio.core

import studio.core.diagnostics.ProjectChecker
import studio.core.image.*
import studio.core.json.Json
import studio.core.pack.Packager
import studio.core.skin.*
import java.nio.file.Files
import kotlin.test.*

class SkinTest {
    private val RED = 0xFFFF0000.toInt()

    @Test fun layoutHasNoOverlapAndStaysInside() {
        for (slim in listOf(false, true)) {
            val count = IntArray(64 * 64)
            for (b in SkinLayout.boxes(slim)) for (r in b.faces().values) {
                assertTrue(r.x >= 0 && r.y >= 0 && r.x + r.w <= 64 && r.y + r.h <= 64, "$b $r")
                for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) count[y * 64 + x]++
            }
            assertTrue(count.all { it <= 1 }, "faces sobrepostas (slim=$slim)")
        }
    }

    @Test fun masksAndPartLookup() {
        assertEquals(1632, SkinLayout.mask(false, false).count())
        assertEquals(1632, SkinLayout.mask(false, true).count())
        assertTrue(SkinLayout.mask(true, false).count() < SkinLayout.mask(false, false).count())
        val head = SkinLayout.partAt(8, 8, false)!!
        assertEquals("head", head.first.part)
        assertFalse(head.first.overlay)
        assertEquals(Face.FRONT, head.second)
        assertTrue(SkinLayout.partAt(40, 8, false)!!.first.overlay)
        assertEquals("body", SkinLayout.partAt(20, 20, false)!!.first.part)
        assertEquals("rightArm", SkinLayout.partAt(44, 20, false)!!.first.part)
        assertNull(SkinLayout.partAt(0, 0, false))
    }

    @Test fun modelCubesFollowTheSlimSetting() {
        val classic = SkinModel.cubes(false)
        val slim = SkinModel.cubes(true)
        assertEquals(12, classic.size)
        assertEquals(0.5f, classic.first { it.part == "head" && it.overlay }.inflate)
        assertEquals(0.25f, classic.first { it.part == "body" && it.overlay }.inflate)
        assertEquals(-8f, classic.first { it.part == "rightArm" && !it.overlay }.x)
        assertEquals(-7f, slim.first { it.part == "rightArm" && !it.overlay }.x)
    }

    @Test fun legacySkinConversionMirrorsLimbs() {
        val src = Bitmap(64, 32)
        src[10, 10] = RED
        src[4, 20] = RED // frente da perna direita, primeiro pixel
        src[44, 20] = RED // frente do braço direito
        val out = SkinConvert.legacyToModern(src)
        assertEquals(64, out.height)
        assertEquals(RED, out[10, 10])
        assertEquals(RED, out[23, 52]) // perna esquerda, espelhada
        assertEquals(RED, out[39, 52]) // braço esquerdo: frente em x=36..39, primeiro pixel vira o último
        assertFailsWith<IllegalArgumentException> { SkinConvert.legacyToModern(Bitmap(64, 64)) }
    }

    @Test fun skinPackIsStructurallyValidAndRoundTrips() = withTempDir { d ->
        val tex = Bitmap(64, 64)
        tex[8, 8] = RED
        val spec = SkinPackSpec(
            "meu_pacote", "Meu Pacote", "Skins de teste",
            listOf(SkinEntry("steve_like", "Steve Like", false, tex), SkinEntry("alex_like", "Alex Like", true, tex.copy())),
        )
        val dir = d.resolve("SP")
        SkinPackBuilder.writeTo(dir, spec)
        for (f in listOf("manifest.json", "skins.json", "texts/en_US.lang", "texts/languages.json", "steve_like.png", "alex_like.png")) {
            assertTrue(Files.exists(dir.resolve(f)), f)
        }
        val skins = Json.parseObject(String(Files.readAllBytes(dir.resolve("skins.json")))).arr("skins")!!.items
        assertEquals(2, skins.size)
        assertTrue(skins.toString().contains("humanoid.customSlim"))
        val lang = String(Files.readAllBytes(dir.resolve("texts/en_US.lang")))
        assertTrue(lang.contains("skin.meu_pacote.alex_like=Alex Like"))
        assertEquals(tex, SkinPackBuilder.readTexture(Files.readAllBytes(dir.resolve("steve_like.png"))))

        val report = ProjectChecker.check(listOf("SP" to dir))
        assertFalse(report.hasErrors, report.errors.joinToString())
        val out = d.resolve("skins.mcpack")
        assertNotNull(Packager.exportMcpack(dir, out).output)
        assertEquals("mcpack", Packager.inspect(out).kind)
    }

    @Test fun skinPackRejectsBadInput() {
        val ok = Bitmap(64, 64)
        fun spec(vararg s: SkinEntry, id: String = "p") = SkinPackSpec(id, "N", "D", s.toList())
        assertFailsWith<IllegalArgumentException> { SkinPackBuilder.build(spec()) }
        assertFailsWith<IllegalArgumentException> { SkinPackBuilder.build(spec(SkinEntry("a", "A", false, ok), SkinEntry("a", "B", false, ok))) }
        assertFailsWith<IllegalArgumentException> { SkinPackBuilder.build(spec(SkinEntry("Bad Id", "A", false, ok))) }
        assertFailsWith<IllegalArgumentException> { SkinPackBuilder.build(spec(SkinEntry("a", "A", false, Bitmap(10, 10)))) }
        assertFailsWith<IllegalArgumentException> { SkinPackBuilder.build(spec(SkinEntry("a", "A", false, ok), id = "Maiúsculas")) }
        assertFailsWith<IllegalArgumentException> { SkinPackBuilder.readTexture(ByteArray(10)) }
    }

    @Test fun validatorWarnsOnUnusualSizes() {
        assertNull(SkinValidator.checkSize(64, 64))
        assertNull(SkinValidator.checkSize(64, 32))
        assertNotNull(SkinValidator.checkSize(100, 100))
    }
}
