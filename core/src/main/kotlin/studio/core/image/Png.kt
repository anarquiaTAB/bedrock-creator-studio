package studio.core.image

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

class PngException(message: String) : Exception(message)

data class PngInfo(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int, val interlaced: Boolean)

/**
 * Leitor/gravador PNG próprio (sem Bitmap do Android nem ImageIO), assim importar/exportar
 * funciona igual no aparelho e nos testes. Lê profundidades 1–16 bits e tipos 0, 2, 3, 4 e 6,
 * com transparência (tRNS). PNG entrelaçado (Adam7) não é suportado e gera erro claro.
 * Grava sempre RGBA 8 bits.
 */
object Png {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    const val HEADER_SIZE = 33

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 255) shl 24) or ((b[o + 1].toInt() and 255) shl 16) or
            ((b[o + 2].toInt() and 255) shl 8) or (b[o + 3].toInt() and 255)

    private fun crcOf(b: ByteArray, from: Int, len: Int): Int {
        val c = CRC32()
        c.update(b, from, len)
        return c.value.toInt()
    }

    private fun checkSignature(b: ByteArray) {
        if (b.size < 8) throw PngException("Arquivo pequeno demais para ser um PNG")
        for (i in 0 until 8) if (b[i] != SIGNATURE[i]) throw PngException("Assinatura PNG inválida (não é um PNG)")
    }

    /** Lê só o cabeçalho (primeiros 33 bytes bastam) e valida o CRC do IHDR. */
    fun readInfo(bytes: ByteArray): PngInfo {
        checkSignature(bytes)
        if (bytes.size < HEADER_SIZE) throw PngException("PNG truncado: cabeçalho incompleto")
        if (be32(bytes, 8) != 13 || String(bytes, 12, 4, Charsets.ISO_8859_1) != "IHDR") {
            throw PngException("PNG inválido: o primeiro bloco deve ser IHDR")
        }
        if (be32(bytes, 29) != crcOf(bytes, 12, 17)) throw PngException("PNG corrompido: CRC do cabeçalho não confere")
        val w = be32(bytes, 16)
        val h = be32(bytes, 20)
        val depth = bytes[24].toInt() and 255
        val type = bytes[25].toInt() and 255
        if (w <= 0 || h <= 0) throw PngException("PNG com dimensões inválidas")
        if ((bytes[26].toInt() and 255) != 0 || (bytes[27].toInt() and 255) != 0) throw PngException("Método de compressão/filtro PNG desconhecido")
        val interlace = bytes[28].toInt() and 255
        if (interlace > 1) throw PngException("Método de entrelaçamento PNG desconhecido")
        val ok = when (type) {
            0 -> depth in intArrayOf(1, 2, 4, 8, 16)
            2, 4, 6 -> depth == 8 || depth == 16
            3 -> depth in intArrayOf(1, 2, 4, 8)
            else -> false
        }
        if (!ok) throw PngException("Combinação inválida de tipo de cor ($type) e profundidade ($depth)")
        return PngInfo(w, h, depth, type, interlace == 1)
    }

    private fun channels(type: Int) = when (type) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; else -> 4 }

    fun decode(bytes: ByteArray): Bitmap {
        val info = readInfo(bytes)
        if (info.interlaced) throw PngException("PNG entrelaçado (Adam7) não é suportado; salve sem entrelaçamento")
        if (info.width.toLong() * info.height > Bitmap.MAX_PIXELS) throw PngException("Imagem grande demais (${info.width}x${info.height})")

        var palette: ByteArray? = null
        var trns: ByteArray? = null
        val idat = ByteArrayOutputStream()
        var pos = 8
        var sawEnd = false
        while (pos + 12 <= bytes.size) {
            val len = be32(bytes, pos)
            if (len < 0 || len.toLong() > bytes.size - pos - 12L) throw PngException("PNG truncado ou com bloco inválido")
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            if (be32(bytes, pos + 8 + len) != crcOf(bytes, pos + 4, len + 4)) throw PngException("PNG corrompido: CRC inválido no bloco $type")
            when (type) {
                "PLTE" -> palette = bytes.copyOfRange(pos + 8, pos + 8 + len)
                "tRNS" -> trns = bytes.copyOfRange(pos + 8, pos + 8 + len)
                "IDAT" -> idat.write(bytes, pos + 8, len)
                "IEND" -> sawEnd = true
            }
            pos += 12 + len
            if (sawEnd) break
        }
        if (!sawEnd) throw PngException("PNG truncado: falta o bloco IEND")
        if (idat.size() == 0) throw PngException("PNG sem dados de imagem")
        if (info.colorType == 3 && (palette == null || palette.size % 3 != 0)) throw PngException("PNG indexado sem paleta válida")

        val ch = channels(info.colorType)
        val bitsPerPixel = ch * info.bitDepth
        val rowBytes = (info.width.toLong() * bitsPerPixel + 7) / 8
        val rawSize = (rowBytes + 1) * info.height
        if (rawSize > Int.MAX_VALUE / 2) throw PngException("Imagem grande demais")
        val raw = ByteArray(rawSize.toInt())
        val inf = Inflater()
        try {
            inf.setInput(idat.toByteArray())
            var off = 0
            while (off < raw.size && !inf.finished()) {
                val n = inf.inflate(raw, off, raw.size - off)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                off += n
            }
            if (off != raw.size) throw PngException("PNG corrompido: dados de imagem incompletos")
        } catch (e: DataFormatException) {
            throw PngException("PNG corrompido: ${e.message}")
        } finally {
            inf.end()
        }

        val data = unfilter(raw, info.height, rowBytes.toInt(), maxOf(1, bitsPerPixel / 8))
        return toBitmap(info, data, rowBytes.toInt(), palette, trns)
    }

    private fun unfilter(raw: ByteArray, h: Int, rowBytes: Int, bpp: Int): ByteArray {
        val data = ByteArray(rowBytes * h)
        val stride = rowBytes + 1
        for (y in 0 until h) {
            val ft = raw[y * stride].toInt() and 255
            val src = y * stride + 1
            val dst = y * rowBytes
            val prev = dst - rowBytes
            for (i in 0 until rowBytes) {
                val x = raw[src + i].toInt() and 255
                val a = if (i >= bpp) data[dst + i - bpp].toInt() and 255 else 0
                val b = if (y > 0) data[prev + i].toInt() and 255 else 0
                val c = if (i >= bpp && y > 0) data[prev + i - bpp].toInt() and 255 else 0
                val v = when (ft) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) shr 1)
                    4 -> x + paeth(a, b, c)
                    else -> throw PngException("Filtro PNG desconhecido ($ft)")
                }
                data[dst + i] = v.toByte()
            }
        }
        return data
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun sample(data: ByteArray, base: Int, idx: Int, depth: Int): Int = when (depth) {
        8 -> data[base + idx].toInt() and 255
        16 -> ((data[base + 2 * idx].toInt() and 255) shl 8) or (data[base + 2 * idx + 1].toInt() and 255)
        else -> {
            val bit = idx * depth
            val byte = data[base + bit / 8].toInt() and 255
            val shift = 8 - depth - (bit % 8)
            (byte shr shift) and ((1 shl depth) - 1)
        }
    }

    private fun to8(v: Int, depth: Int): Int = when (depth) {
        8 -> v
        16 -> v ushr 8
        else -> v * 255 / ((1 shl depth) - 1)
    }

    private fun toBitmap(info: PngInfo, data: ByteArray, rowBytes: Int, palette: ByteArray?, trns: ByteArray?): Bitmap {
        val w = info.width
        val d = info.bitDepth
        val bmp = Bitmap(w, info.height)
        val ch = channels(info.colorType)
        fun key(i: Int): Int? =
            if (trns != null && trns.size >= 2 * i + 2) ((trns[2 * i].toInt() and 255) shl 8) or (trns[2 * i + 1].toInt() and 255) else null
        for (y in 0 until info.height) {
            val base = y * rowBytes
            for (x in 0 until w) {
                val s = x * ch
                val argb = when (info.colorType) {
                    6 -> Colors.argb(
                        to8(sample(data, base, s + 3, d), d), to8(sample(data, base, s, d), d),
                        to8(sample(data, base, s + 1, d), d), to8(sample(data, base, s + 2, d), d),
                    )
                    2 -> {
                        val r = sample(data, base, s, d)
                        val g = sample(data, base, s + 1, d)
                        val b = sample(data, base, s + 2, d)
                        val transparent = trns != null && trns.size >= 6 && key(0) == r && key(1) == g && key(2) == b
                        Colors.argb(if (transparent) 0 else 255, to8(r, d), to8(g, d), to8(b, d))
                    }
                    0 -> {
                        val v = sample(data, base, s, d)
                        val g = to8(v, d)
                        Colors.argb(if (key(0) == v) 0 else 255, g, g, g)
                    }
                    4 -> {
                        val g = to8(sample(data, base, s, d), d)
                        Colors.argb(to8(sample(data, base, s + 1, d), d), g, g, g)
                    }
                    else -> {
                        val idx = sample(data, base, s, d)
                        val pal = palette!!
                        if (idx * 3 + 2 >= pal.size) throw PngException("Índice de paleta fora do intervalo")
                        val a = if (trns != null && idx < trns.size) trns[idx].toInt() and 255 else 255
                        Colors.argb(a, pal[idx * 3].toInt() and 255, pal[idx * 3 + 1].toInt() and 255, pal[idx * 3 + 2].toInt() and 255)
                    }
                }
                bmp[x, y] = argb
            }
        }
        return bmp
    }

    fun encode(bmp: Bitmap): ByteArray {
        val raw = ByteArray((bmp.width * 4 + 1) * bmp.height)
        var o = 0
        for (y in 0 until bmp.height) {
            raw[o++] = 0
            for (x in 0 until bmp.width) {
                val c = bmp[x, y]
                raw[o++] = Colors.red(c).toByte()
                raw[o++] = Colors.green(c).toByte()
                raw[o++] = Colors.blue(c).toByte()
                raw[o++] = Colors.alpha(c).toByte()
            }
        }
        val zbuf = ByteArrayOutputStream()
        DeflaterOutputStream(zbuf, Deflater(Deflater.DEFAULT_COMPRESSION)).use { it.write(raw) }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val ihdr = ByteArray(13)
        putBe32(ihdr, 0, bmp.width)
        putBe32(ihdr, 4, bmp.height)
        ihdr[8] = 8
        ihdr[9] = 6
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", zbuf.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val head = ByteArray(4)
        putBe32(head, 0, data.size)
        out.write(head)
        val typed = type.toByteArray(Charsets.ISO_8859_1) + data
        out.write(typed)
        val crc = ByteArray(4)
        putBe32(crc, 0, crcOf(typed, 0, typed.size))
        out.write(crc)
    }
}
