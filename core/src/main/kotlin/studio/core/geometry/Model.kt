package studio.core.geometry

import studio.core.json.JsonObj

/** Faces de um cubo, com os nomes usados no formato de geometria do Bedrock. */
enum class FaceName(val key: String) {
    NORTH("north"), SOUTH("south"), EAST("east"), WEST("west"), UP("up"), DOWN("down");

    companion object {
        fun fromKey(k: String): FaceName? = values().firstOrNull { it.key == k }
    }
}

/**
 * UV de uma face. [w]/[h] nulos = "uv_size" ausente no arquivo (o jogo decide o padrão); o estúdio não inventa um valor.
 * [raw] guarda campos que a interface não conhece (ex.: material_instance, uv_rotation).
 */
data class FaceUv(val u: Double, val v: Double, val w: Double? = null, val h: Double? = null, val raw: JsonObj = JsonObj.empty())

sealed interface CubeUv {
    /** UV de caixa: o jogo desdobra as seis faces a partir de (u, v) usando o tamanho do cubo. */
    data class Box(val u: Double, val v: Double) : CubeUv

    /** UV por face. [raw] mantém chaves desconhecidas do objeto "uv". */
    data class PerFace(val faces: Map<FaceName, FaceUv>, val raw: JsonObj = JsonObj.empty()) : CubeUv
}

data class Cube(
    val origin: Vec3,
    val size: Vec3,
    val uv: CubeUv,
    val pivot: Vec3? = null,
    val rotation: Vec3? = null,
    val inflate: Double? = null,
    val mirror: Boolean? = null,
    /** Objeto original: campos que a interface não conhece sobrevivem à regravação. */
    val raw: JsonObj = JsonObj.empty(),
)

data class Bone(
    val name: String,
    val parent: String?,
    val pivot: Vec3,
    val rotation: Vec3? = null,
    val mirror: Boolean? = null,
    val inflate: Double? = null,
    val cubes: List<Cube> = emptyList(),
    val raw: JsonObj = JsonObj.empty(),
)

data class Geometry(
    val identifier: String,
    val textureWidth: Int?,
    val textureHeight: Int?,
    val bones: List<Bone>,
    val descriptionRaw: JsonObj = JsonObj.empty(),
    val raw: JsonObj = JsonObj.empty(),
) {
    fun bone(name: String): Bone? = bones.firstOrNull { it.name == name }
    fun hasBone(name: String) = bones.any { it.name == name }
    fun children(name: String): List<Bone> = bones.filter { it.parent == name }
    fun roots(): List<Bone> = bones.filter { it.parent == null || !hasBone(it.parent) }

    /** Todos os descendentes de [name] (sem incluir o próprio), protegidos contra ciclos. */
    fun descendants(name: String): List<Bone> {
        val out = ArrayList<Bone>()
        val seen = HashSet<String>().apply { add(name) }
        val queue = ArrayDeque<String>().apply { add(name) }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (c in children(cur)) if (seen.add(c.name)) {
                out.add(c)
                queue.add(c.name)
            }
        }
        return out
    }

    /** Ancestrais de [name], do pai mais próximo até a raiz; para se encontrar um ciclo. */
    fun ancestors(name: String): List<Bone> {
        val out = ArrayList<Bone>()
        val seen = HashSet<String>().apply { add(name) }
        var cur = bone(name)?.parent
        while (cur != null && seen.add(cur)) {
            val b = bone(cur) ?: break
            out.add(b)
            cur = b.parent
        }
        return out
    }

    /** true se [name] participa de um ciclo de parentesco. */
    fun inCycle(name: String): Boolean {
        val seen = HashSet<String>().apply { add(name) }
        var cur = bone(name)?.parent
        while (cur != null) {
            if (cur == name) return true
            if (!seen.add(cur)) return false
            cur = bone(cur)?.parent
        }
        return false
    }

    /** Ossos com os pais sempre antes dos filhos (ordem estável); ossos em ciclo vêm no fim. */
    fun orderedBones(): List<Bone> {
        val out = ArrayList<Bone>()
        val done = HashSet<String>()
        fun visit(b: Bone) {
            if (!done.add(b.name)) return
            out.add(b)
            for (c in children(b.name)) visit(c)
        }
        roots().forEach { visit(it) }
        bones.forEach { if (it.name !in done) { done.add(it.name); out.add(it) } }
        return out
    }

    val cubeCount: Int get() = bones.sumOf { it.cubes.size }
}
