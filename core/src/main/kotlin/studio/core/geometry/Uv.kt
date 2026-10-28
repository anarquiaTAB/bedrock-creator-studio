package studio.core.geometry

import studio.core.image.Bitmap
import studio.core.image.Selection
import studio.core.util.EditException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Retângulo de textura em pixels do espaço "texture_width x texture_height" da geometria. */
data class UvRect(
    val x: Double, val y: Double, val w: Double, val h: Double,
    val flipU: Boolean = false, val flipV: Boolean = false,
)

data class CubeRef(val bone: String, val index: Int)

/**
 * Desdobramento de caixa. A posição de cada face na planificação segue o que as ferramentas de modelagem
 * do Bedrock usam (leste, norte, oeste, sul em sequência, cima/baixo acima), mas isto foi escrito de memória:
 * CONFIRME no jogo antes de depender da orientação. Todo o estúdio usa esta tabela, então basta corrigir aqui.
 */
object BoxUv {
    private fun cell(v: Double) = floor(abs(v))

    /** Largura e altura da planificação inteira da caixa (2*(x+z) por z+y). */
    fun footprint(size: Vec3): Pair<Int, Int> {
        val sx = cell(size.x); val sy = cell(size.y); val sz = cell(size.z)
        return (2 * (sx + sz)).toInt() to (sz + sy).toInt()
    }

    fun faces(size: Vec3, u: Double, v: Double, mirror: Boolean): Map<FaceName, UvRect> {
        val sx = cell(size.x); val sy = cell(size.y); val sz = cell(size.z)
        val f = mirror
        val east = UvRect(u, v + sz, sz, sy, f)
        val west = UvRect(u + sz + sx, v + sz, sz, sy, f)
        return linkedMapOf(
            FaceName.UP to UvRect(u + sz, v, sx, sz, f),
            FaceName.DOWN to UvRect(u + sz + sx, v, sx, sz, f),
            FaceName.EAST to if (mirror) west else east,
            FaceName.NORTH to UvRect(u + sz, v + sz, sx, sy, f),
            FaceName.WEST to if (mirror) east else west,
            FaceName.SOUTH to UvRect(u + 2 * sz + sx, v + sz, sx, sy, f),
        )
    }

    /** Tamanho "natural" da face em pixels de textura, usado quando uv_size não existe. */
    fun defaultFaceSize(face: FaceName, size: Vec3): Pair<Double, Double> {
        val sx = cell(size.x); val sy = cell(size.y); val sz = cell(size.z)
        return when (face) {
            FaceName.NORTH, FaceName.SOUTH -> sx to sy
            FaceName.EAST, FaceName.WEST -> sz to sy
            FaceName.UP, FaceName.DOWN -> sx to sz
        }
    }
}

object CubeUvs {
    /** Retângulos de textura de cada face do cubo (aplica o espelhamento do cubo/osso quando for UV de caixa). */
    fun faceRects(cube: Cube, boneMirror: Boolean?): Map<FaceName, UvRect> = when (val uv = cube.uv) {
        is CubeUv.Box -> BoxUv.faces(cube.size, uv.u, uv.v, cube.mirror ?: boneMirror ?: false)
        is CubeUv.PerFace -> {
            val out = LinkedHashMap<FaceName, UvRect>()
            for ((face, f) in uv.faces) {
                val dflt = BoxUv.defaultFaceSize(face, cube.size)
                val w = f.w ?: dflt.first
                val h = f.h ?: dflt.second
                out[face] = UvRect(if (w < 0) f.u + w else f.u, if (h < 0) f.v + h else f.v, abs(w), abs(h), w < 0, h < 0)
            }
            out
        }
    }
}

class LayoutResult(val geometry: Geometry, val textureChanged: Boolean)

object UvLayout {
    private fun texW(g: Geometry) = g.textureWidth ?: 0
    private fun texH(g: Geometry) = g.textureHeight ?: 0

    private fun forEachPixel(r: UvRect, w: Int, h: Int, f: (Int, Int) -> Unit) {
        val x0 = max(0, floor(r.x + 1e-9).toInt())
        val y0 = max(0, floor(r.y + 1e-9).toInt())
        val x1 = min(w, Math.ceil(r.x + r.w - 1e-9).toInt())
        val y1 = min(h, Math.ceil(r.y + r.h - 1e-9).toInt())
        for (y in y0 until y1) for (x in x0 until x1) f(x, y)
    }

    /** Pixels usados pelos cubos (união das faces). Exige texture_width/height. */
    fun usedMask(g: Geometry, skip: (String, Int) -> Boolean = { _, _ -> false }): BooleanArray {
        val w = texW(g); val h = texH(g)
        val m = BooleanArray(max(0, w * h))
        if (w == 0 || h == 0) return m
        for (b in g.bones) b.cubes.forEachIndexed { i, c ->
            if (skip(b.name, i)) return@forEachIndexed
            for (r in CubeUvs.faceRects(c, b.mirror).values) forEachPixel(r, w, h) { x, y -> m[y * w + x] = true }
        }
        return m
    }

    /** Máscara (para o editor de texturas) das regiões da textura usadas pelo modelo. */
    fun textureMask(g: Geometry): Selection {
        require(texW(g) > 0 && texH(g) > 0) { "A geometria não declara texture_width/texture_height." }
        return Selection(texW(g), texH(g), usedMask(g))
    }

    /** Cubos/faces que usam o pixel (x, y) da textura (para destacar no modelo o que se está pintando). */
    fun owners(g: Geometry, x: Int, y: Int): List<Pair<CubeRef, FaceName>> {
        val out = ArrayList<Pair<CubeRef, FaceName>>()
        for (b in g.bones) b.cubes.forEachIndexed { i, c ->
            for ((face, r) in CubeUvs.faceRects(c, b.mirror)) {
                if (x + 0.5 >= r.x && x + 0.5 < r.x + r.w && y + 0.5 >= r.y && y + 0.5 < r.y + r.h) out.add(CubeRef(b.name, i) to face)
            }
        }
        return out
    }

    class Overlap(val a: CubeRef, val b: CubeRef, val pixels: Int)

    /**
     * Pares de cubos cujas faces se sobrepõem parcialmente na textura. Cubos com o mesmo UV e o mesmo tamanho
     * (pernas/braços espelhados que compartilham a textura de propósito) não contam.
     */
    fun overlaps(g: Geometry): List<Overlap> {
        val w = texW(g); val h = texH(g)
        if (w == 0 || h == 0) return emptyList()
        val refs = ArrayList<Triple<CubeRef, Cube, Boolean?>>()
        for (b in g.bones) b.cubes.forEachIndexed { i, c -> refs.add(Triple(CubeRef(b.name, i), c, b.mirror)) }
        val owner = HashMap<Int, MutableList<Int>>()
        refs.forEachIndexed { idx, (_, c, bm) ->
            val seen = HashSet<Int>()
            for (r in CubeUvs.faceRects(c, bm).values) forEachPixel(r, w, h) { x, y -> seen.add(y * w + x) }
            for (p in seen) owner.getOrPut(p) { ArrayList() }.add(idx)
        }
        val counts = HashMap<Pair<Int, Int>, Int>()
        for (list in owner.values) if (list.size > 1) {
            for (i in list.indices) for (j in i + 1 until list.size) counts.merge(list[i] to list[j], 1, Int::plus)
        }
        return counts.entries
            .filter { (k, _) ->
                val a = refs[k.first].second; val b = refs[k.second].second
                !(a.uv == b.uv && a.size == b.size) // compartilhamento intencional
            }
            .map { (k, n) -> Overlap(refs[k.first].first, refs[k.second].first, n) }
            .sortedWith(compareBy({ it.a.bone }, { it.a.index }, { it.b.bone }, { it.b.index }))
    }

    private fun firstFit(mask: BooleanArray, w: Int, h: Int, fw: Int, fh: Int): Pair<Int, Int>? {
        if (fw > w || fh > h) return null
        var y = 0
        while (y + fh <= h) {
            var x = 0
            while (x + fw <= w) {
                var blockedX = -1
                loop@ for (yy in y until y + fh) for (xx in x + fw - 1 downTo x) {
                    if (mask[yy * w + xx]) { blockedX = xx; break@loop }
                }
                if (blockedX < 0) return x to y
                x = blockedX + 1
            }
            y++
        }
        return null
    }

    /** Próxima posição livre para uma caixa nova de [size]; null se não couber. */
    fun findFreeBox(g: Geometry, size: Vec3, skip: (String, Int) -> Boolean = { _, _ -> false }): Pair<Int, Int>? {
        val w = texW(g); val h = texH(g)
        if (w == 0 || h == 0) return null
        val (fw, fh) = BoxUv.footprint(size)
        return firstFit(usedMask(g, skip), w, h, fw, fh)
    }

    /**
     * Reorganiza os UVs de caixa para que não se sobreponham. Cubos com UV por face não são movidos (ocupam espaço).
     * Se não couber e [grow] for true, dobra a menor dimensão até caber (máx. 4096). Cubos que compartilhavam
     * um UV de propósito (mesmo UV e tamanho) continuam compartilhando.
     */
    fun autoLayout(g: Geometry, grow: Boolean = false): LayoutResult {
        var w = texW(g); var h = texH(g)
        if (w == 0 || h == 0) throw EditException("Defina texture_width e texture_height antes de organizar os UVs.")
        data class Item(val ref: CubeRef, val cube: Cube, val fw: Int, val fh: Int, val group: Pair<Vec3, CubeUv.Box>)
        val items = ArrayList<Item>()
        for (b in g.bones) b.cubes.forEachIndexed { i, c ->
            val uv = c.uv as? CubeUv.Box ?: return@forEachIndexed
            val (fw, fh) = BoxUv.footprint(c.size)
            items.add(Item(CubeRef(b.name, i), c, fw, fh, c.size to uv))
        }
        // um grupo = cubos que compartilham UV e tamanho; só o primeiro de cada grupo ocupa espaço novo
        val groups = items.groupBy { it.group }.values.toList()
            .sortedWith(compareByDescending<List<Item>> { it[0].fh }.thenByDescending { it[0].fw })
        while (true) {
            val mask = BooleanArray(w * h)
            val gg = g.copy(textureWidth = w, textureHeight = h)
            for (b in g.bones) b.cubes.forEach { c ->
                if (c.uv is CubeUv.PerFace) for (r in CubeUvs.faceRects(c, b.mirror).values) forEachPixel(r, w, h) { x, y -> mask[y * w + x] = true }
            }
            val placed = HashMap<CubeRef, Pair<Int, Int>>()
            var ok = true
            for (grp in groups) {
                val it0 = grp[0]
                val pos = firstFit(mask, w, h, it0.fw, it0.fh)
                if (pos == null) { ok = false; break }
                for (yy in pos.second until pos.second + it0.fh) for (xx in pos.first until pos.first + it0.fw) mask[yy * w + xx] = true
                for (m in grp) placed[m.ref] = pos
            }
            if (ok) {
                val bones = gg.bones.map { b ->
                    b.copy(cubes = b.cubes.mapIndexed { i, c ->
                        val p = placed[CubeRef(b.name, i)]
                        if (p == null) c else c.copy(uv = CubeUv.Box(p.first.toDouble(), p.second.toDouble()))
                    })
                }
                return LayoutResult(gg.copy(bones = bones), w != texW(g) || h != texH(g))
            }
            if (!grow || (w >= 4096 && h >= 4096)) {
                throw EditException("Os cubos não cabem em textura ${texW(g)}x${texH(g)}. Aumente a textura ou use a opção de crescer automaticamente.")
            }
            if (w <= h && w < 4096) w *= 2 else h *= 2
        }
    }

    /**
     * Acompanha a reorganização: copia os pixels de cada cubo de caixa da posição antiga ([before]) para a nova
     * ([after]) numa textura do tamanho de [after]. [tex] pode ter resolução maior que texture_width (proporção mantida).
     */
    fun remapTexture(before: Geometry, after: Geometry, tex: Bitmap): Bitmap {
        val bw = texW(before); val bh = texH(before)
        val aw = texW(after); val ah = texH(after)
        if (bw == 0 || bh == 0 || aw == 0 || ah == 0) throw EditException("Geometria sem tamanho de textura.")
        val sx = tex.width.toDouble() / bw
        val sy = tex.height.toDouble() / bh
        val out = Bitmap(max(1, Math.round(aw * sx).toInt()), max(1, Math.round(ah * sy).toInt()))
        for (b in before.bones) {
            val ab = after.bone(b.name) ?: continue
            b.cubes.forEachIndexed { i, c ->
                val from = c.uv as? CubeUv.Box ?: return@forEachIndexed
                val to = ab.cubes.getOrNull(i)?.uv as? CubeUv.Box ?: return@forEachIndexed
                val (fw, fh) = BoxUv.footprint(c.size)
                val pw = Math.round(fw * sx).toInt(); val ph = Math.round(fh * sy).toInt()
                val ox = Math.round(from.u * sx).toInt(); val oy = Math.round(from.v * sy).toInt()
                val nx = Math.round(to.u * sx).toInt(); val ny = Math.round(to.v * sy).toInt()
                for (yy in 0 until ph) for (xx in 0 until pw) {
                    if (tex.inBounds(ox + xx, oy + yy) && out.inBounds(nx + xx, ny + yy)) out[nx + xx, ny + yy] = tex[ox + xx, oy + yy]
                }
            }
        }
        return out
    }
}
