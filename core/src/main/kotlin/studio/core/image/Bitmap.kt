package studio.core.image

import kotlin.math.abs
import kotlin.math.max

/** Cores como Int ARGB não pré-multiplicado (0xAARRGGBB). */
object Colors {
    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 255) shl 24) or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255)

    fun alpha(c: Int) = c ushr 24
    fun red(c: Int) = (c shr 16) and 255
    fun green(c: Int) = (c shr 8) and 255
    fun blue(c: Int) = c and 255

    /** Composição "source over" de [src] sobre [dst]. */
    fun over(src: Int, dst: Int): Int {
        val sa = alpha(src)
        if (sa == 255) return src
        if (sa == 0) return dst
        val da = alpha(dst)
        val outA = sa + (da * (255 - sa) + 127) / 255
        if (outA == 0) return 0
        fun ch(s: Int, d: Int): Int {
            val num = s * sa * 255 + d * da * (255 - sa)
            return ((num + (outA * 255) / 2) / (outA * 255)).coerceIn(0, 255)
        }
        return argb(outA, ch(red(src), red(dst)), ch(green(src), green(dst)), ch(blue(src), blue(dst)))
    }

    fun withOpacity(c: Int, opacity: Int): Int {
        if (opacity >= 255) return c
        val a = (alpha(c) * opacity.coerceAtLeast(0) + 127) / 255
        return (a shl 24) or (c and 0x00FFFFFF)
    }

    /** Maior diferença entre canais (0..255). */
    fun distance(a: Int, b: Int): Int = max(
        max(abs(alpha(a) - alpha(b)), abs(red(a) - red(b))),
        max(abs(green(a) - green(b)), abs(blue(a) - blue(b))),
    )

    /** Aceita #RRGGBB ou #RRGGBBAA. */
    fun parseHex(text: String): Int {
        val t = text.trim().removePrefix("#")
        require(t.length == 6 || t.length == 8) { "Cor inválida: '$text' (use #RRGGBB ou #RRGGBBAA)" }
        require(t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "Cor inválida: '$text'" }
        val rgb = t.substring(0, 6).toInt(16)
        val a = if (t.length == 8) t.substring(6, 8).toInt(16) else 255
        return (a shl 24) or rgb
    }

    fun toHex(c: Int): String {
        val rgb = String.format("#%02X%02X%02X", red(c), green(c), blue(c))
        return if (alpha(c) == 255) rgb else rgb + String.format("%02X", alpha(c))
    }
}

class Bitmap(val width: Int, val height: Int, val pixels: IntArray) {
    constructor(width: Int, height: Int) : this(width, height, IntArray(checkedSize(width, height)))

    init {
        require(pixels.size == checkedSize(width, height)) { "Tamanho do vetor de pixels não confere" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, color: Int) {
        pixels[y * width + x] = color
    }

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun copy() = Bitmap(width, height, pixels.copyOf())
    fun fill(color: Int) = pixels.fill(color)

    fun crop(x: Int, y: Int, w: Int, h: Int): Bitmap {
        val out = Bitmap(w, h)
        for (j in 0 until h) for (i in 0 until w) {
            if (inBounds(x + i, y + j)) out[i, j] = this[x + i, y + j]
        }
        return out
    }

    /** Desenha [src] em (dx, dy) com composição "over"; partes fora dos limites são ignoradas. */
    fun blit(src: Bitmap, dx: Int, dy: Int) {
        for (j in 0 until src.height) for (i in 0 until src.width) {
            val x = dx + i
            val y = dy + j
            if (inBounds(x, y)) this[x, y] = Colors.over(src[i, j], this[x, y])
        }
    }

    fun flipped(horizontal: Boolean): Bitmap {
        val out = Bitmap(width, height)
        for (y in 0 until height) for (x in 0 until width) {
            if (horizontal) out[width - 1 - x, y] = this[x, y] else out[x, height - 1 - y] = this[x, y]
        }
        return out
    }

    fun rotated90(clockwise: Boolean): Bitmap {
        val out = Bitmap(height, width)
        for (y in 0 until height) for (x in 0 until width) {
            if (clockwise) out[height - 1 - y, x] = this[x, y] else out[y, width - 1 - x] = this[x, y]
        }
        return out
    }

    /** Redimensiona sem suavização (preserva pixel art). */
    fun scaledNearest(newW: Int, newH: Int): Bitmap {
        val out = Bitmap(newW, newH)
        for (y in 0 until newH) {
            val sy = (y.toLong() * height / newH).toInt()
            for (x in 0 until newW) {
                out[x, y] = this[(x.toLong() * width / newW).toInt(), sy]
            }
        }
        return out
    }

    override fun equals(other: Any?) =
        other is Bitmap && width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    override fun hashCode() = 31 * (31 * width + height) + pixels.contentHashCode()

    companion object {
        /** Limite de memória (não de projetos): 4096 x 4096 pixels. */
        const val MAX_PIXELS = 4096 * 4096

        fun checkedSize(w: Int, h: Int): Int {
            require(w >= 1 && h >= 1) { "Dimensões inválidas: ${w}x$h" }
            require(w.toLong() * h <= MAX_PIXELS) { "Imagem grande demais: ${w}x$h" }
            return w * h
        }
    }
}
