package studio.core.render

import studio.core.geometry.*
import studio.core.image.Bitmap
import studio.core.image.Colors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Renderizador por software (CPU) para a prévia 3D: texturiza cada face com a imagem REAL da textura usando os UVs reais
 * da geometria, com z-buffer e teste de alfa (recorte). Não depende de OpenGL e roda em qualquer aparelho. É uma prévia,
 * não o motor do jogo: sem sombras, sem iluminação real, sem materiais do jogo.
 */
object SoftRenderer {
    class Options(
        val width: Int,
        val height: Int,
        val background: Int = 0,
        /** Cubos a destacar (ex.: o cubo selecionado no editor). */
        val highlight: Set<CubeRef> = emptySet(),
        val highlightColor: Int = 0xFFFF9800.toInt(),
    )

    private fun shade(face: FaceName) = when (face) {
        FaceName.UP -> 1.0; FaceName.DOWN -> 0.5; FaceName.NORTH, FaceName.SOUTH -> 0.8; FaceName.EAST, FaceName.WEST -> 0.65
    }

    private class Vtx(val x: Double, val y: Double, val iz: Double, val uz: Double, val vz: Double)

    fun render(g: Geometry, poses: Map<String, BonePose>, texture: Bitmap?, camera: OrbitCamera, opt: Options): Bitmap {
        val out = Bitmap(opt.width, opt.height)
        out.fill(opt.background)
        val depth = DoubleArray(opt.width * opt.height) // 1/z; 0 = vazio
        val tw = (g.textureWidth ?: texture?.width ?: 16).toDouble()
        val th = (g.textureHeight ?: texture?.height ?: 16).toDouble()
        val sx = if (texture != null) texture.width / tw else 1.0
        val sy = if (texture != null) texture.height / th else 1.0
        for (pc in Posing.compute(g, poses)) {
            val rects = CubeUvs.faceRects(pc.cube, pc.boneMirror)
            val marked = CubeRef(pc.bone, pc.index) in opt.highlight
            for ((face, r) in rects) {
                val quad = pc.faceQuad(face)
                val sp = quad.map { camera.project(it, opt.width, opt.height) ?: return@map null }
                if (sp.any { it == null }) continue
                val x0 = if (r.flipU) r.x + r.w else r.x
                val x1 = if (r.flipU) r.x else r.x + r.w
                val y0 = if (r.flipV) r.y + r.h else r.y
                val y1 = if (r.flipV) r.y else r.y + r.h
                val uvs = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
                val v = sp.mapIndexed { i, p ->
                    val iz = 1.0 / p!!.depth
                    Vtx(p.x, p.y, iz, uvs[i].first * iz, uvs[i].second * iz)
                }
                val shadeF = shade(face)
                for (tri in listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 3))) {
                    raster(out, depth, v[tri[0]], v[tri[1]], v[tri[2]]) { u, vv ->
                        var c = if (texture == null) 0xFFB0B4BA.toInt() else {
                            val px = floor(u * sx).toInt().coerceIn(0, texture.width - 1)
                            val py = floor(vv * sy).toInt().coerceIn(0, texture.height - 1)
                            texture[px, py]
                        }
                        if (Colors.alpha(c) < 128) return@raster 0
                        c = Colors.argb(
                            255,
                            (Colors.red(c) * shadeF).toInt(), (Colors.green(c) * shadeF).toInt(), (Colors.blue(c) * shadeF).toInt(),
                        )
                        if (marked) c = tint(c, opt.highlightColor, 0.4)
                        c
                    }
                }
            }
        }
        return out
    }

    private fun tint(c: Int, t: Int, k: Double): Int = Colors.argb(
        255,
        (Colors.red(c) * (1 - k) + Colors.red(t) * k).toInt(),
        (Colors.green(c) * (1 - k) + Colors.green(t) * k).toInt(),
        (Colors.blue(c) * (1 - k) + Colors.blue(t) * k).toInt(),
    )

    private fun edge(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double) = (px - ax) * (by - ay) - (py - ay) * (bx - ax)

    /** [shader] devolve a cor ARGB do pixel (u, v em pixels de textura) ou 0 para descartá-lo. */
    private inline fun raster(out: Bitmap, depth: DoubleArray, a: Vtx, b: Vtx, c: Vtx, shader: (Double, Double) -> Int) {
        val area = edge(a.x, a.y, b.x, b.y, c.x, c.y)
        if (kotlin.math.abs(area) < 1e-9) return
        val minX = max(0, floor(min(a.x, min(b.x, c.x))).toInt())
        val maxX = min(out.width - 1, ceil(max(a.x, max(b.x, c.x))).toInt())
        val minY = max(0, floor(min(a.y, min(b.y, c.y))).toInt())
        val maxY = min(out.height - 1, ceil(max(a.y, max(b.y, c.y))).toInt())
        for (y in minY..maxY) for (x in minX..maxX) {
            val px = x + 0.5; val py = y + 0.5
            var w0 = edge(b.x, b.y, c.x, c.y, px, py) / area
            var w1 = edge(c.x, c.y, a.x, a.y, px, py) / area
            var w2 = edge(a.x, a.y, b.x, b.y, px, py) / area
            if (w0 < -1e-9 || w1 < -1e-9 || w2 < -1e-9) continue
            val iz = w0 * a.iz + w1 * b.iz + w2 * c.iz
            val idx = y * out.width + x
            if (iz <= depth[idx]) continue
            val u = (w0 * a.uz + w1 * b.uz + w2 * c.uz) / iz
            val v = (w0 * a.vz + w1 * b.vz + w2 * c.vz) / iz
            val col = shader(u, v)
            if (col == 0) continue
            depth[idx] = iz
            out[x, y] = col
        }
    }
}
