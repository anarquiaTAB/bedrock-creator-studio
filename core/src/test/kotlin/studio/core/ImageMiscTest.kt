package studio.core

import studio.core.diagnostics.ProjectChecker
import studio.core.image.*
import java.nio.file.Files
import kotlin.test.*

class ImageMiscTest {
    @Test fun colorHelpers() {
        assertEquals(0xFF112233.toInt(), Colors.parseHex("#112233"))
        assertEquals(0x80112233.toInt(), Colors.parseHex("11223380"))
        assertEquals("#112233", Colors.toHex(0xFF112233.toInt()))
        assertEquals("#11223380", Colors.toHex(0x80112233.toInt()))
        assertFailsWith<IllegalArgumentException> { Colors.parseHex("#12") }
        assertFailsWith<IllegalArgumentException> { Colors.parseHex("#GGGGGG") }
        assertEquals(0xFFFF0000.toInt(), Colors.over(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
        assertEquals(0xFF00FF00.toInt(), Colors.over(0, 0xFF00FF00.toInt()))
        assertEquals(10, Colors.distance(0xFF000000.toInt(), 0xFF0A0000.toInt()))
    }

    @Test fun bitmapOps() {
        val b = Bitmap(3, 2)
        b[0, 0] = 1
        assertEquals(1, b.flipped(true)[2, 0])
        assertEquals(1, b.flipped(false)[0, 1])
        assertEquals(1, b.rotated90(true)[1, 0])
        assertEquals(1, b.rotated90(false)[0, 2])
        assertEquals(4, b.scaledNearest(6, 4).pixels.count { it == 1 })
        assertFailsWith<IllegalArgumentException> { Bitmap(0, 5) }
        assertFailsWith<IllegalArgumentException> { Bitmap(5000, 5000) }
    }

    @Test fun documentFileRoundTripAndCorruption() {
        val d = Document(5, 4)
        val l1 = d.addLayer("Fundo ç")
        d.addLayer("Topo").also { it.opacity = 100; it.visible = false }
        l1.bitmap[2, 2] = 0xFF123456.toInt()
        val back = DocumentIO.read(DocumentIO.write(d))
        assertEquals(2, back.layers.size)
        assertEquals("Fundo ç", back.layers[0].name)
        assertEquals(0xFF123456.toInt(), back.layers[0].bitmap[2, 2])
        assertEquals(100, back.layers[1].opacity)
        assertFalse(back.layers[1].visible)
        val id = back.addLayer("Nova").id
        assertTrue(back.layers.map { it.id }.toSet().size == 3 && id > l1.id)
        assertFailsWith<ImageFormatException> { DocumentIO.read(ByteArray(20)) }
        val good = DocumentIO.write(d)
        assertFailsWith<ImageFormatException> { DocumentIO.read(good.copyOf(good.size / 2)) }
    }

    @Test fun paletteParseAndExtract() {
        val p = Palette.parse("p", "// teste\n#FF0000\n\n#00FF0080\n")
        assertEquals(listOf(0xFFFF0000.toInt(), 0x8000FF00.toInt()), p.colors)
        assertEquals(p.colors, Palette.parse("q", p.toText()).colors)
        val e = assertFailsWith<IllegalArgumentException> { Palette.parse("x", "#FF0000\nzzz") }
        assertTrue(e.message!!.contains("Linha 2"))
        val b = Bitmap(2, 2)
        b[0, 0] = 0xFFFF0000.toInt(); b[1, 0] = 0xFFFF0000.toInt(); b[0, 1] = 0xFF0000FF.toInt()
        assertEquals(listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt()), Palette.fromBitmap("img", b).colors)
    }

    @Test fun diagnosticsFlagBrokenPngButAcceptValidOnes() = withTempDir { d ->
        val s = Samples.create(d)
        assertFalse(ProjectChecker.check(listOf("RP" to s.rp)).items.any { it.code == "IMAGE_INVALID" })
        Files.write(s.rp.resolve("textures/items/broken.png"), ByteArray(100) { 7 })
        val r = ProjectChecker.check(listOf("RP" to s.rp))
        val e = r.errors.first { it.code == "IMAGE_INVALID" }
        assertEquals("RP/textures/items/broken.png", e.file)
    }

    @Test fun editedTextureExportsAsRealPng() = withTempDir { d ->
        val ed = Editor(Document(16, 16).also { it.addLayer("L") })
        ed.rect(0, 0, 7, 7, 0xFF336699.toInt(), true)
        val png = Png.encode(ed.doc.flatten())
        Files.write(d.resolve("t.png"), png)
        val back = Png.decode(Files.readAllBytes(d.resolve("t.png")))
        assertEquals(0xFF336699.toInt(), back[3, 3])
        assertEquals(0, back[12, 12])
    }
}
