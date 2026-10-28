package studio.core.geometry

import studio.core.util.EditException
import studio.core.util.History

/** Operações puras sobre [Geometry]: cada uma devolve uma nova geometria ou lança [EditException] sem alterar nada. */
object GeometryOps {
    private fun fail(msg: String): Nothing = throw EditException(msg)

    private fun finite(v: Vec3, what: String) {
        if (!(v.x.isFinite() && v.y.isFinite() && v.z.isFinite())) fail("$what contém valor inválido.")
    }

    private fun checkName(g: Geometry, name: String, ignore: String? = null) {
        if (name.isBlank()) fail("O nome do osso não pode ficar vazio.")
        if (name != name.trim() || name.any { it.isWhitespace() && it != ' ' }) fail("O nome do osso não pode ter espaços nas pontas nem quebras de linha.")
        // nomes de osso não diferenciam maiúsculas de minúsculas no jogo; evitamos nomes que só diferem na caixa
        val clash = g.bones.firstOrNull { it.name.equals(name, ignoreCase = true) && it.name != ignore }
        if (clash != null) fail("Já existe um osso chamado '${clash.name}'.")
    }

    private fun need(g: Geometry, name: String): Bone = g.bone(name) ?: fail("Osso '$name' não existe.")

    private fun mapBone(g: Geometry, name: String, f: (Bone) -> Bone): Geometry {
        need(g, name)
        return g.copy(bones = g.bones.map { if (it.name == name) f(it) else it })
    }

    private fun mapCube(g: Geometry, bone: String, index: Int, f: (Cube) -> Cube): Geometry {
        val b = need(g, bone)
        if (index !in b.cubes.indices) fail("O osso '$bone' não tem o cubo $index.")
        return mapBone(g, bone) { it.copy(cubes = it.cubes.mapIndexed { i, c -> if (i == index) f(c) else c }) }
    }

    // ---- geometria ----

    fun setIdentifier(g: Geometry, id: String): Geometry {
        if (!id.startsWith("geometry.") || id.length <= "geometry.".length || id.any { it.isWhitespace() }) {
            fail("O identificador deve começar com 'geometry.' e não ter espaços.")
        }
        return g.copy(identifier = id)
    }

    /** Altera o tamanho da textura. Com [scaleUv], os UVs acompanham a proporção. */
    fun setTextureSize(g: Geometry, w: Int, h: Int, scaleUv: Boolean): Geometry {
        if (w < 1 || h < 1 || w > 16384 || h > 16384) fail("Tamanho de textura inválido: ${w}x$h.")
        if (!scaleUv || g.textureWidth == null || g.textureHeight == null) return g.copy(textureWidth = w, textureHeight = h)
        val fx = w.toDouble() / g.textureWidth
        val fy = h.toDouble() / g.textureHeight
        fun face(f: FaceUv) = f.copy(u = f.u * fx, v = f.v * fy, w = f.w?.times(fx), h = f.h?.times(fy))
        return g.copy(
            textureWidth = w, textureHeight = h,
            bones = g.bones.map { b ->
                b.copy(cubes = b.cubes.map { c ->
                    when (val uv = c.uv) {
                        is CubeUv.Box -> c.copy(uv = CubeUv.Box(uv.u * fx, uv.v * fy))
                        is CubeUv.PerFace -> c.copy(uv = uv.copy(faces = uv.faces.mapValues { face(it.value) }))
                    }
                })
            },
        )
    }

    // ---- ossos ----

    fun addBone(g: Geometry, name: String, parent: String? = null, pivot: Vec3? = null): Geometry {
        checkName(g, name)
        if (parent != null) need(g, parent)
        val p = pivot ?: parent?.let { g.bone(it)!!.pivot } ?: Vec3.ZERO
        finite(p, "O pivô")
        return g.copy(bones = g.bones + Bone(name, parent, p))
    }

    /** Remove o osso. Com [removeChildren] false, os filhos passam para o pai do osso removido. */
    fun removeBone(g: Geometry, name: String, removeChildren: Boolean): Geometry {
        val b = need(g, name)
        val doomed = if (removeChildren) (g.descendants(name).map { it.name } + name).toSet() else setOf(name)
        return g.copy(bones = g.bones.filter { it.name !in doomed }.map {
            if (!removeChildren && it.parent == name) it.copy(parent = b.parent) else it
        })
    }

    fun renameBone(g: Geometry, old: String, new: String): Geometry {
        need(g, old)
        if (old == new) return g
        checkName(g, new, ignore = old)
        return g.copy(bones = g.bones.map {
            val renamed = if (it.name == old) it.copy(name = new) else it
            if (renamed.parent == old) renamed.copy(parent = new) else renamed
        })
    }

    fun reparent(g: Geometry, name: String, newParent: String?): Geometry {
        need(g, name)
        if (newParent != null) {
            need(g, newParent)
            if (newParent == name || g.descendants(name).any { it.name == newParent }) fail("Um osso não pode ser filho de si mesmo ou de um descendente.")
        }
        return mapBone(g, name) { it.copy(parent = newParent) }
    }

    /** Translada o osso (pivô e cubos) e, por padrão, todos os descendentes. */
    fun moveBone(g: Geometry, name: String, delta: Vec3, withChildren: Boolean = true): Geometry {
        need(g, name)
        finite(delta, "O deslocamento")
        val targets = if (withChildren) (g.descendants(name).map { it.name } + name).toSet() else setOf(name)
        return g.copy(bones = g.bones.map { b ->
            if (b.name !in targets) b else b.copy(
                pivot = b.pivot + delta,
                cubes = b.cubes.map { c -> c.copy(origin = c.origin + delta, pivot = c.pivot?.plus(delta)) },
            )
        })
    }

    fun setPivot(g: Geometry, name: String, pivot: Vec3): Geometry {
        finite(pivot, "O pivô")
        return mapBone(g, name) { it.copy(pivot = pivot) }
    }

    fun setBoneRotation(g: Geometry, name: String, rotation: Vec3?): Geometry {
        rotation?.let { finite(it, "A rotação") }
        return mapBone(g, name) { it.copy(rotation = rotation?.takeIf { r -> !r.isZero() || it.rotation != null }) }
    }

    fun setBoneMirror(g: Geometry, name: String, mirror: Boolean?) = mapBone(g, name) { it.copy(mirror = mirror) }

    fun setBoneInflate(g: Geometry, name: String, inflate: Double?): Geometry {
        if (inflate != null && !inflate.isFinite()) fail("Inflate inválido.")
        return mapBone(g, name) { it.copy(inflate = inflate) }
    }

    // ---- cubos ----

    /** Adiciona um cubo. Sem [uv], procura uma região livre na textura (ou usa 0,0 se não houver espaço/tamanho de textura). */
    fun addCube(g: Geometry, bone: String, origin: Vec3, size: Vec3, uv: CubeUv? = null): Geometry {
        need(g, bone)
        finite(origin, "A origem"); finite(size, "O tamanho")
        val chosen = uv ?: (UvLayout.findFreeBox(g, size)?.let { CubeUv.Box(it.first.toDouble(), it.second.toDouble()) } ?: CubeUv.Box(0.0, 0.0))
        return mapBone(g, bone) { it.copy(cubes = it.cubes + Cube(origin, size, chosen)) }
    }

    fun removeCube(g: Geometry, bone: String, index: Int): Geometry {
        mapCube(g, bone, index) { it } // valida
        return mapBone(g, bone) { it.copy(cubes = it.cubes.filterIndexed { i, _ -> i != index }) }
    }

    fun updateCube(g: Geometry, bone: String, index: Int, f: (Cube) -> Cube): Geometry =
        mapCube(g, bone, index) { c ->
            val n = f(c)
            finite(n.origin, "A origem"); finite(n.size, "O tamanho")
            n.pivot?.let { finite(it, "O pivô do cubo") }
            n.rotation?.let { finite(it, "A rotação do cubo") }
            n
        }

    fun moveCube(g: Geometry, bone: String, index: Int, delta: Vec3): Geometry =
        updateCube(g, bone, index) { it.copy(origin = it.origin + delta, pivot = it.pivot?.plus(delta)) }

    fun resizeCube(g: Geometry, bone: String, index: Int, size: Vec3): Geometry =
        updateCube(g, bone, index) { it.copy(size = size) }

    fun setCubeUv(g: Geometry, bone: String, index: Int, uv: CubeUv): Geometry =
        updateCube(g, bone, index) { it.copy(uv = uv) }

    /** Duplica o cubo (com [offset]) no mesmo osso e põe a cópia numa região livre da textura se o UV for de caixa. */
    fun duplicateCube(g: Geometry, bone: String, index: Int, offset: Vec3 = Vec3.ZERO): Geometry {
        val c = mapCube(g, bone, index) { it }.bone(bone)!!.cubes[index]
        val copy = c.copy(origin = c.origin + offset, pivot = c.pivot?.plus(offset))
        return mapBone(g, bone) { it.copy(cubes = it.cubes + copy) }
    }

    fun moveCubeToBone(g: Geometry, bone: String, index: Int, target: String): Geometry {
        need(g, target)
        if (target == bone) return g
        val c = mapCube(g, bone, index) { it }.bone(bone)!!.cubes[index]
        return removeCube(g, bone, index).let { r -> mapBone(r, target) { it.copy(cubes = it.cubes + c) } }
    }

    // ---- espelhamento ----

    fun mirrorName(name: String): String = when {
        name.contains("left") -> name.replace("left", "right")
        name.contains("right") -> name.replace("right", "left")
        name.contains("Left") -> name.replace("Left", "Right")
        name.contains("Right") -> name.replace("Right", "Left")
        name.endsWith("_l") -> name.dropLast(2) + "_r"
        name.endsWith("_r") -> name.dropLast(2) + "_l"
        else -> name + "_mirror"
    }

    /**
     * Copia o osso e seus descendentes refletidos no plano X = [about] (simetria esquerda/direita).
     * Os nomes passam por [nameOf]; a raiz da cópia fica sob [newParent] (padrão: o mesmo pai). Os UVs de caixa
     * mantêm a mesma região e o espelhamento é feito pela marca "mirror"; UVs por face trocam leste/oeste e invertem a largura.
     */
    fun mirrorSubtree(
        g: Geometry, root: String, about: Double = 0.0,
        nameOf: (String) -> String = ::mirrorName, newParent: String? = need(g, root).parent,
    ): Geometry {
        val src = listOf(need(g, root)) + g.descendants(root)
        val names = src.associate { it.name to nameOf(it.name) }
        var out = g
        for ((old, new) in names) {
            if (old == new) fail("O nome espelhado de '$old' é igual ao original; escolha outro nome.")
            if (out.bones.any { it.name.equals(new, true) }) fail("Já existe um osso chamado '$new'.")
        }
        if (names.values.toSet().size != names.size) fail("Os nomes espelhados colidem entre si.")
        if (newParent != null) need(g, newParent)
        fun refl(x: Double) = 2 * about - x
        fun flipFaces(uv: CubeUv.PerFace): CubeUv.PerFace {
            val m = LinkedHashMap<FaceName, FaceUv>()
            for ((face, f) in uv.faces) {
                val target = when (face) { FaceName.EAST -> FaceName.WEST; FaceName.WEST -> FaceName.EAST; else -> face }
                val dflt = f.w
                m[target] = if (dflt != null) f.copy(u = f.u + dflt, w = -dflt) else f
            }
            return uv.copy(faces = m)
        }
        val copies = src.map { b ->
            b.copy(
                name = names.getValue(b.name),
                parent = if (b.name == root) newParent else b.parent?.let { names[it] ?: it },
                pivot = Vec3(refl(b.pivot.x), b.pivot.y, b.pivot.z),
                rotation = b.rotation?.let { Vec3(it.x, -it.y + 0.0, -it.z + 0.0) },
                mirror = !(b.mirror ?: false),
                cubes = b.cubes.map { c ->
                    c.copy(
                        origin = Vec3(refl(c.origin.x + c.size.x), c.origin.y, c.origin.z),
                        pivot = c.pivot?.let { Vec3(refl(it.x), it.y, it.z) },
                        rotation = c.rotation?.let { Vec3(it.x, -it.y + 0.0, -it.z + 0.0) },
                        mirror = c.mirror?.not(),
                        uv = (c.uv as? CubeUv.PerFace)?.let { flipFaces(it) } ?: c.uv,
                    )
                },
            )
        }
        out = out.copy(bones = out.bones + copies)
        return out
    }
}

/** Editor com histórico. Cada método é uma única ação desfazível; erros lançam [EditException] e nada muda. */
class GeometryEditor(initial: Geometry, maxHistory: Int = 200) {
    private val history = History(initial, maxHistory)
    val geometry: Geometry get() = history.state
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    val undoLabel get() = history.undoLabel
    val redoLabel get() = history.redoLabel
    fun undo() = history.undo()
    fun redo() = history.redo()

    private fun edit(label: String, f: (Geometry) -> Geometry): Geometry = history.edit(label, f)

    fun setIdentifier(id: String) = edit("Alterar identificador") { GeometryOps.setIdentifier(it, id) }
    fun setTextureSize(w: Int, h: Int, scaleUv: Boolean = false) = edit("Tamanho da textura") { GeometryOps.setTextureSize(it, w, h, scaleUv) }
    fun addBone(name: String, parent: String? = null, pivot: Vec3? = null) = edit("Adicionar osso") { GeometryOps.addBone(it, name, parent, pivot) }
    fun removeBone(name: String, removeChildren: Boolean = false) = edit("Remover osso") { GeometryOps.removeBone(it, name, removeChildren) }
    fun renameBone(old: String, new: String) = edit("Renomear osso") { GeometryOps.renameBone(it, old, new) }
    fun reparent(name: String, newParent: String?) = edit("Mudar pai do osso") { GeometryOps.reparent(it, name, newParent) }
    fun moveBone(name: String, delta: Vec3, withChildren: Boolean = true) = edit("Mover osso") { GeometryOps.moveBone(it, name, delta, withChildren) }
    fun setPivot(name: String, pivot: Vec3) = edit("Mover pivô") { GeometryOps.setPivot(it, name, pivot) }
    fun setBoneRotation(name: String, rotation: Vec3?) = edit("Rotacionar osso") { GeometryOps.setBoneRotation(it, name, rotation) }
    fun setBoneMirror(name: String, mirror: Boolean?) = edit("Espelhar osso") { GeometryOps.setBoneMirror(it, name, mirror) }
    fun setBoneInflate(name: String, inflate: Double?) = edit("Inflate do osso") { GeometryOps.setBoneInflate(it, name, inflate) }
    fun addCube(bone: String, origin: Vec3, size: Vec3, uv: CubeUv? = null) = edit("Adicionar cubo") { GeometryOps.addCube(it, bone, origin, size, uv) }
    fun removeCube(bone: String, index: Int) = edit("Remover cubo") { GeometryOps.removeCube(it, bone, index) }
    fun updateCube(bone: String, index: Int, f: (Cube) -> Cube) = edit("Editar cubo") { GeometryOps.updateCube(it, bone, index, f) }
    fun moveCube(bone: String, index: Int, delta: Vec3) = edit("Mover cubo") { GeometryOps.moveCube(it, bone, index, delta) }
    fun resizeCube(bone: String, index: Int, size: Vec3) = edit("Redimensionar cubo") { GeometryOps.resizeCube(it, bone, index, size) }
    fun setCubeUv(bone: String, index: Int, uv: CubeUv) = edit("Editar UV") { GeometryOps.setCubeUv(it, bone, index, uv) }
    fun duplicateCube(bone: String, index: Int, offset: Vec3 = Vec3.ZERO) = edit("Duplicar cubo") { GeometryOps.duplicateCube(it, bone, index, offset) }
    fun moveCubeToBone(bone: String, index: Int, target: String) = edit("Mover cubo para outro osso") { GeometryOps.moveCubeToBone(it, bone, index, target) }
    fun mirrorSubtree(root: String, about: Double = 0.0, nameOf: (String) -> String = GeometryOps::mirrorName) =
        edit("Espelhar osso") { GeometryOps.mirrorSubtree(it, root, about, nameOf) }

    /** Reorganiza os UVs de caixa; devolve o resultado para que quem chama também reorganize a textura ([UvLayout.remapTexture]). */
    fun autoLayoutUv(grow: Boolean = false): LayoutResult {
        var result: LayoutResult? = null
        edit("Organizar UVs") { g -> UvLayout.autoLayout(g, grow).also { result = it }.geometry }
        return result ?: LayoutResult(geometry, false)
    }
}
