package studio.core.image

/** Máscara de seleção do tamanho do documento. */
class Selection(val width: Int, val height: Int, val mask: BooleanArray) {
    init {
        require(mask.size == width * height) { "Máscara com tamanho incorreto" }
    }

    fun contains(x: Int, y: Int) = x in 0 until width && y in 0 until height && mask[y * width + x]
    fun isEmpty() = mask.none { it }
    fun count() = mask.count { it }

    /** (minX, minY, maxX, maxY) ou null se vazia. */
    fun bounds(): IntArray? {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) for (x in 0 until width) {
            if (mask[y * width + x]) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX, maxY)
    }

    fun inverted() = Selection(width, height, BooleanArray(mask.size) { !mask[it] })

    fun union(other: Selection): Selection {
        require(width == other.width && height == other.height)
        return Selection(width, height, BooleanArray(mask.size) { mask[it] || other.mask[it] })
    }

    fun translated(dx: Int, dy: Int): Selection {
        val out = BooleanArray(mask.size)
        for (y in 0 until height) for (x in 0 until width) {
            if (mask[y * width + x]) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height) out[ny * width + nx] = true
            }
        }
        return Selection(width, height, out)
    }

    companion object {
        fun all(w: Int, h: Int) = Selection(w, h, BooleanArray(w * h) { true })

        fun rect(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Selection {
            val lx = minOf(x0, x1)
            val hx = maxOf(x0, x1)
            val ly = minOf(y0, y1)
            val hy = maxOf(y0, y1)
            val m = BooleanArray(w * h)
            for (y in maxOf(0, ly)..minOf(h - 1, hy)) for (x in maxOf(0, lx)..minOf(w - 1, hx)) m[y * w + x] = true
            return Selection(w, h, m)
        }

        fun ellipse(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Selection {
            val m = BooleanArray(w * h)
            Shapes.ellipse(x0, y0, x1, y1, filled = true) { x, y ->
                if (x in 0 until w && y in 0 until h) m[y * w + x] = true
            }
            return Selection(w, h, m)
        }
    }
}

/** Pixels copiados; [mask] indica quais pixels do retângulo fazem parte do recorte. */
class Clip(val width: Int, val height: Int, val pixels: IntArray, val mask: BooleanArray)

class Palette(val name: String, val colors: List<Int>) {
    fun toText(): String = colors.joinToString("\n") { Colors.toHex(it) } + "\n"

    companion object {
        /** Uma cor por linha (#RRGGBB ou #RRGGBBAA); linhas vazias e começadas por // são ignoradas. */
        fun parse(name: String, text: String): Palette {
            val colors = mutableListOf<Int>()
            text.lines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) return@forEachIndexed
                try {
                    colors.add(Colors.parseHex(line))
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Linha ${i + 1}: ${e.message}")
                }
            }
            return Palette(name, colors)
        }

        /** Cores não transparentes da imagem, da mais usada para a menos usada (até [max]). */
        fun fromBitmap(name: String, bmp: Bitmap, max: Int = 64): Palette {
            val counts = LinkedHashMap<Int, Int>()
            for (c in bmp.pixels) if (Colors.alpha(c) != 0) counts[c] = (counts[c] ?: 0) + 1
            return Palette(name, counts.entries.sortedByDescending { it.value }.take(max).map { it.key })
        }
    }
}
