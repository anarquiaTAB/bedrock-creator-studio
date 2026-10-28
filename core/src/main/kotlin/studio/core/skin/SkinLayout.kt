package studio.core.skin

import studio.core.image.Selection

enum class Face { TOP, BOTTOM, RIGHT, FRONT, LEFT, BACK }

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Int, py: Int) = px >= x && px < x + w && py >= y && py < y + h
}

/**
 * Caixa do mapa de textura de uma parte do corpo: origem (u, v) e dimensões (w largura, h altura, d profundidade)
 * no layout clássico de 64x64. As seis faces seguem o desdobramento padrão de caixas do Minecraft.
 */
data class SkinBox(val part: String, val overlay: Boolean, val u: Int, val v: Int, val w: Int, val h: Int, val d: Int) {
    fun faces(): Map<Face, Rect> = linkedMapOf(
        Face.TOP to Rect(u + d, v, w, d),
        Face.BOTTOM to Rect(u + d + w, v, w, d),
        Face.RIGHT to Rect(u, v + d, d, h),
        Face.FRONT to Rect(u + d, v + d, w, h),
        Face.LEFT to Rect(u + d + w, v + d, d, h),
        Face.BACK to Rect(u + 2 * d + w, v + d, w, h),
    )
}

object SkinLayout {
    const val SIZE = 64

    /** Todas as caixas do layout 64x64 (base e camada externa). Braços slim têm 3 pixels de largura. */
    fun boxes(slim: Boolean): List<SkinBox> {
        val arm = if (slim) 3 else 4
        return listOf(
            SkinBox("head", false, 0, 0, 8, 8, 8),
            SkinBox("head", true, 32, 0, 8, 8, 8),
            SkinBox("body", false, 16, 16, 8, 12, 4),
            SkinBox("body", true, 16, 32, 8, 12, 4),
            SkinBox("rightArm", false, 40, 16, arm, 12, 4),
            SkinBox("rightArm", true, 40, 32, arm, 12, 4),
            SkinBox("rightLeg", false, 0, 16, 4, 12, 4),
            SkinBox("rightLeg", true, 0, 32, 4, 12, 4),
            SkinBox("leftArm", false, 32, 48, arm, 12, 4),
            SkinBox("leftArm", true, 48, 48, arm, 12, 4),
            SkinBox("leftLeg", false, 16, 48, 4, 12, 4),
            SkinBox("leftLeg", true, 0, 48, 4, 12, 4),
        )
    }

    /** Máscara (64x64) das áreas da camada base ([overlay] = false) ou da camada externa. */
    fun mask(slim: Boolean, overlay: Boolean): Selection {
        val m = BooleanArray(SIZE * SIZE)
        for (b in boxes(slim)) {
            if (b.overlay != overlay) continue
            for (r in b.faces().values) {
                for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) m[y * SIZE + x] = true
            }
        }
        return Selection(SIZE, SIZE, m)
    }

    /** Qual parte/face do corpo usa o pixel (x, y); null se for área não utilizada. */
    fun partAt(x: Int, y: Int, slim: Boolean): Pair<SkinBox, Face>? {
        for (b in boxes(slim)) for ((face, r) in b.faces()) if (r.contains(x, y)) return b to face
        return null
    }
}
