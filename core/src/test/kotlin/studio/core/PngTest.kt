package studio.core

import studio.core.image.*
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.test.*

class PngTest {
    private fun pattern(w: Int, h: Int): Bitmap {
        val b = Bitmap(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            b[x, y] = Colors.argb((x * 37 + y * 11) % 256, (x * 7 + y * 3) % 256, (x * x + y) % 256, (x * 13 + y * y) % 256)
        }
        return b
    }

    private fun pngBytes(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(img, "png", out))
        return out.toByteArray()
    }

    private fun assertSame(img: BufferedImage, bmp: Bitmap) {
        assertEquals(img.width, bmp.width)
        assertEquals(img.height, bmp.height)
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            assertEquals(img.getRGB(x, y), bmp[x, y], "pixel ($x,$y)")
        }
    }

    @Test fun roundTripKeepsEveryPixelIncludingAlpha() {
        val b = pattern(37, 21)
        assertEquals(b, Png.decode(Png.encode(b)))
    }

    @Test fun javaImageIoReadsOurPng() {
        val b = pattern(20, 13)
        val img = ImageIO.read(ByteArrayInputStream(Png.encode(b)))
        assertSame(img, b)
    }

    @Test fun weReadImageIoArgbAndRgb() {
        val src = pattern(40, 30)
        val argb = BufferedImage(40, 30, BufferedImage.TYPE_INT_ARGB)
        val rgb = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 30) for (x in 0 until 40) {
            argb.setRGB(x, y, src[x, y])
            rgb.setRGB(x, y, src[x, y])
        }
        assertSame(argb, Png.decode(pngBytes(argb)))
        assertSame(rgb, Png.decode(pngBytes(rgb)))
    }

    @Test fun weReadIndexedPalettesWithTransparency() {
        for (bits in listOf(8, 4)) {
            val n = 1 shl bits
            val r = ByteArray(n) { (it * 16).toByte() }
            val g = ByteArray(n) { (255 - it * 9).toByte() }
            val bl = ByteArray(n) { (it * 5).toByte() }
            val a = ByteArray(n) { if (it % 3 == 0) 0 else (255 - it).toByte() }
            val cm = IndexColorModel(bits, n, r, g, bl, a)
            val type = if (bits == 8) BufferedImage.TYPE_BYTE_INDEXED else BufferedImage.TYPE_BYTE_BINARY
            val img = BufferedImage(17, 9, type, cm)
            for (y in 0 until 9) for (x in 0 until 17) img.raster.setSample(x, y, 0, (x + y * 3) % n)
            assertSame(img, Png.decode(pngBytes(img)))
        }
    }

    @Test fun readInfoReportsHeader() {
        val info = Png.readInfo(Png.encode(Bitmap(5, 7)))
        assertEquals(5, info.width)
        assertEquals(7, info.height)
        assertEquals(6, info.colorType)
        assertFalse(info.interlaced)
    }

    @Test fun invalidFilesAreRejectedWithPngException() {
        val good = Png.encode(pattern(8, 8))
        assertFailsWith<PngException> { Png.decode(ByteArray(0)) }
        assertFailsWith<PngException> { Png.decode("não sou png, mas tenho mais de 33 bytes mesmo".toByteArray()) }
        assertFailsWith<PngException> { Png.decode(good.copyOf(good.size - 20)) }
        val badCrc = good.copyOf()
        badCrc[40] = (badCrc[40].toInt() xor 0x55).toByte()
        assertFailsWith<PngException> { Png.decode(badCrc) }
        val interlaced = good.copyOf()
        interlaced[28] = 1
        val crc = CRC32().also { it.update(interlaced, 12, 17) }.value.toInt()
        for (i in 0 until 4) interlaced[29 + i] = (crc ushr (24 - 8 * i)).toByte()
        assertFailsWith<PngException> { Png.decode(interlaced) }
    }
}
