package studio.core.image

/** Geradores de pixels das ferramentas (sem alocar listas): cada pixel é entregue a [plot]. */
object Shapes {
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, plot: (Int, Int) -> Unit) {
        var x = x0
        var y = y0
        val dx = Math.abs(x1 - x0)
        val dy = -Math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            plot(x, y)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) {
                err += dy
                x += sx
            }
            if (e2 <= dx) {
                err += dx
                y += sy
            }
        }
    }

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, filled: Boolean, plot: (Int, Int) -> Unit) {
        val lx = minOf(x0, x1)
        val hx = maxOf(x0, x1)
        val ly = minOf(y0, y1)
        val hy = maxOf(y0, y1)
        for (y in ly..hy) for (x in lx..hx) {
            if (filled || x == lx || x == hx || y == ly || y == hy) plot(x, y)
        }
    }

    private fun inside(x: Int, y: Int, cx: Double, cy: Double, rx: Double, ry: Double): Boolean {
        val nx = (x + 0.5 - cx) / rx
        val ny = (y + 0.5 - cy) / ry
        return nx * nx + ny * ny <= 1.0
    }

    /** Elipse inscrita no retângulo (x0,y0)-(x1,y1), inclusive. */
    fun ellipse(x0: Int, y0: Int, x1: Int, y1: Int, filled: Boolean, plot: (Int, Int) -> Unit) {
        val lx = minOf(x0, x1)
        val hx = maxOf(x0, x1)
        val ly = minOf(y0, y1)
        val hy = maxOf(y0, y1)
        val cx = (lx + hx + 1) / 2.0
        val cy = (ly + hy + 1) / 2.0
        val rx = (hx - lx + 1) / 2.0
        val ry = (hy - ly + 1) / 2.0
        for (y in ly..hy) for (x in lx..hx) {
            if (!inside(x, y, cx, cy, rx, ry)) continue
            if (filled || !inside(x - 1, y, cx, cy, rx, ry) || !inside(x + 1, y, cx, cy, rx, ry) ||
                !inside(x, y - 1, cx, cy, rx, ry) || !inside(x, y + 1, cx, cy, rx, ry)
            ) {
                plot(x, y)
            }
        }
    }

    /** Ponta do pincel de [size] pixels centrada em (cx, cy). */
    fun stamp(size: Int, round: Boolean, cx: Int, cy: Int, plot: (Int, Int) -> Unit) {
        val n = size.coerceAtLeast(1)
        val x0 = cx - (n - 1) / 2
        val y0 = cy - (n - 1) / 2
        if (!round || n <= 2) {
            rect(x0, y0, x0 + n - 1, y0 + n - 1, true, plot)
        } else {
            ellipse(x0, y0, x0 + n - 1, y0 + n - 1, true, plot)
        }
    }
}
