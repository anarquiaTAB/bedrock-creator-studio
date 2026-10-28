package studio.core.skin

/** Cubo do modelo de jogador para a prévia 3D: posição mínima, tamanho e inflação em pixels de modelo. */
data class SkinCube(
    val part: String,
    val overlay: Boolean,
    val x: Float, val y: Float, val z: Float,
    val w: Float, val h: Float, val d: Float,
    val inflate: Float,
    val box: SkinBox,
) {
    /** Retângulo de textura de cada face; a prévia deve amostrar a imagem realmente editada. */
    fun uv(): Map<Face, Rect> = box.faces()
}

/**
 * Geometria da prévia (apenas visualização aproximada, não o motor do jogo).
 * Convenção: y para cima, pés em y=0, frente do personagem voltada para -z, direita do personagem em -x.
 * Inflação da camada externa: 0,5 no chapéu e 0,25 nas demais partes (valores a conferir no jogo).
 */
object SkinModel {
    fun cubes(slim: Boolean): List<SkinCube> {
        val arm = if (slim) 3f else 4f
        val result = mutableListOf<SkinCube>()
        for (b in SkinLayout.boxes(slim)) {
            val (x, y, z) = when (b.part) {
                "head" -> Triple(-4f, 24f, -4f)
                "body" -> Triple(-4f, 12f, -2f)
                "rightArm" -> Triple(-4f - arm, 12f, -2f)
                "leftArm" -> Triple(4f, 12f, -2f)
                "rightLeg" -> Triple(-4f, 0f, -2f)
                else -> Triple(0f, 0f, -2f) // leftLeg
            }
            val inflate = if (!b.overlay) 0f else if (b.part == "head") 0.5f else 0.25f
            result.add(SkinCube(b.part, b.overlay, x, y, z, b.w.toFloat(), b.h.toFloat(), b.d.toFloat(), inflate, b))
        }
        return result
    }
}
