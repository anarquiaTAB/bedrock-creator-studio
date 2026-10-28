package studio.core.animation

import studio.core.geometry.Vec3
import studio.core.util.EditException
import studio.core.util.History
import studio.core.util.Num
import kotlin.math.abs

/** Operações puras sobre [Animation]: devolvem uma nova animação ou lançam [EditException]. */
object AnimationOps {
    private fun fail(msg: String): Nothing = throw EditException(msg)

    /** Os tempos são guardados com 4 casas (0,0001 s), a mesma precisão da escrita do arquivo. */
    fun quantize(t: Double): Double {
        if (!t.isFinite()) fail("Tempo inválido.")
        return Math.round(t * 10_000.0) / 10_000.0
    }

    private fun sameTime(a: Double, b: Double) = abs(a - b) < 0.00005

    fun checkName(name: String) {
        if (!name.startsWith("animation.") || name.length <= "animation.".length || name.any { it.isWhitespace() }) {
            fail("O nome da animação deve começar com 'animation.' e não ter espaços (ex.: animation.golem.walk).")
        }
    }

    fun setName(a: Animation, name: String): Animation { checkName(name); return a.copy(name = name) }

    fun setLoop(a: Animation, loop: Loop) = a.copy(loop = loop)

    /** [length] nulo remove animation_length (vale o último quadro-chave). Com [trim], quadros além do novo fim são removidos. */
    fun setLength(a: Animation, length: Double?, trim: Boolean = false): Animation {
        if (length != null && (!length.isFinite() || length <= 0)) fail("A duração deve ser maior que zero.")
        val l = length?.let { quantize(it) }
        var out = a.copy(length = l)
        if (trim && l != null) {
            out = out.copy(bones = out.bones.mapValues { (_, b) ->
                var nb = b
                for (k in ChannelKind.values()) nb.channel(k)?.let { ch ->
                    if (!ch.isConstant) {
                        val kept = ch.keys.filter { it.time <= l + 0.00005 }
                        nb = nb.with(k, if (kept.isEmpty()) null else ch.copy(keys = kept))
                    }
                }
                nb
            }.filterValues { it.hasChannels || it.raw.fields.isNotEmpty() })
        }
        return out
    }

    /** Define o tamanho como o do último quadro-chave. */
    fun fitLength(a: Animation): Animation {
        val last = a.bones.values.maxOfOrNull { b -> ChannelKind.values().maxOfOrNull { k -> b.channel(k)?.lastTime ?: 0.0 } ?: 0.0 } ?: 0.0
        if (last <= 0.0) fail("Não há quadros-chave para medir a duração.")
        return a.copy(length = quantize(last))
    }

    private fun update(a: Animation, bone: String, f: (BoneAnim) -> BoneAnim): Animation {
        val cur = a.bones[bone] ?: BoneAnim()
        val nb = f(cur)
        val m = LinkedHashMap(a.bones)
        if (!nb.hasChannels && nb.raw.fields.isEmpty()) m.remove(bone) else m[bone] = nb
        return a.copy(bones = m)
    }

    private fun checkBoneName(name: String) {
        if (name.isBlank() || name != name.trim()) fail("Nome de osso inválido.")
    }

    /** Insere ou substitui o quadro-chave em [time]. Se o canal era constante, a constante vira um quadro-chave em 0. */
    fun setKeyframe(a: Animation, bone: String, kind: ChannelKind, time: Double, value: AVec, lerpMode: String? = null): Animation {
        checkBoneName(bone)
        val t = quantize(time)
        if (t < 0) fail("O tempo não pode ser negativo.")
        return update(a, bone) { b ->
            val ch = b.channel(kind)
            val base = when {
                ch == null -> emptyList()
                ch.isConstant -> if (sameTime(t, 0.0)) emptyList() else listOf(Keyframe(0.0, ch.constant!!))
                else -> ch.keys
            }
            val existing = base.indexOfFirst { sameTime(it.time, t) }
            val keys = if (existing >= 0) {
                base.mapIndexed { i, k -> if (i == existing) k.copy(time = t, pre = value, post = value, lerpMode = lerpMode ?: k.lerpMode) else k }
            } else base + Keyframe(t, value, value, lerpMode)
            b.with(kind, Channel(keys = keys.sortedBy { it.time }))
        }
    }

    private fun keys(a: Animation, bone: String, kind: ChannelKind): List<Keyframe> {
        val ch = a.bones[bone]?.channel(kind) ?: fail("'$bone' não tem animação de ${kind.key}.")
        if (ch.isConstant) fail("O canal ${kind.key} de '$bone' é constante (sem quadros-chave).")
        return ch.keys
    }

    private fun idx(keys: List<Keyframe>, i: Int) {
        if (i !in keys.indices) fail("Quadro-chave $i não existe.")
    }

    fun deleteKeyframe(a: Animation, bone: String, kind: ChannelKind, index: Int): Animation {
        val k = keys(a, bone, kind)
        idx(k, index)
        val rest = k.filterIndexed { i, _ -> i != index }
        return update(a, bone) { it.with(kind, if (rest.isEmpty()) null else Channel(keys = rest)) }
    }

    fun moveKeyframe(a: Animation, bone: String, kind: ChannelKind, index: Int, newTime: Double): Animation {
        val k = keys(a, bone, kind)
        idx(k, index)
        val t = quantize(newTime)
        if (t < 0) fail("O tempo não pode ser negativo.")
        if (k.withIndex().any { (i, x) -> i != index && sameTime(x.time, t) }) fail("Já existe um quadro-chave em ${Num.fmtTime(t)}.")
        val moved = k.mapIndexed { i, x -> if (i == index) x.copy(time = t) else x }.sortedBy { it.time }
        return update(a, bone) { it.with(kind, Channel(keys = moved)) }
    }

    fun setKeyframeValue(a: Animation, bone: String, kind: ChannelKind, index: Int, pre: AVec?, post: AVec): Animation {
        val k = keys(a, bone, kind)
        idx(k, index)
        return update(a, bone) { b -> b.with(kind, Channel(keys = k.mapIndexed { i, x -> if (i == index) x.copy(pre = pre ?: post, post = post) else x })) }
    }

    fun setKeyframeLerp(a: Animation, bone: String, kind: ChannelKind, index: Int, mode: String?): Animation {
        val k = keys(a, bone, kind)
        idx(k, index)
        val m = mode?.takeIf { it != LerpMode.LINEAR }
        return update(a, bone) { b -> b.with(kind, Channel(keys = k.mapIndexed { i, x -> if (i == index) x.copy(lerpMode = m) else x })) }
    }

    /** Troca o canal por um valor constante (sem linha do tempo). */
    fun setConstant(a: Animation, bone: String, kind: ChannelKind, value: AVec): Animation {
        checkBoneName(bone)
        return update(a, bone) { it.with(kind, Channel(constant = value)) }
    }

    fun removeChannel(a: Animation, bone: String, kind: ChannelKind): Animation {
        if (a.bones[bone]?.channel(kind) == null) fail("'$bone' não tem animação de ${kind.key}.")
        return update(a, bone) { it.with(kind, null) }
    }

    fun removeBone(a: Animation, bone: String): Animation {
        if (bone !in a.bones) fail("'$bone' não está na animação.")
        return a.copy(bones = LinkedHashMap(a.bones).also { it.remove(bone) })
    }

    fun renameBone(a: Animation, old: String, new: String): Animation {
        checkBoneName(new)
        if (old !in a.bones) fail("'$old' não está na animação.")
        if (old == new) return a
        if (a.bones.keys.any { it.equals(new, true) }) fail("Já existe '$new' na animação.")
        val m = LinkedHashMap<String, BoneAnim>()
        for ((k, v) in a.bones) m[if (k == old) new else k] = v
        return a.copy(bones = m)
    }

    private fun mapAllKeys(a: Animation, f: (Keyframe) -> Keyframe): Animation = a.copy(bones = a.bones.mapValues { (_, b) ->
        var nb = b
        for (k in ChannelKind.values()) nb.channel(k)?.let { ch -> if (!ch.isConstant) nb = nb.with(k, Channel(keys = ch.keys.map(f).sortedBy { it.time })) }
        nb
    })

    /** Multiplica todos os tempos (e a duração) por [factor]. */
    fun scaleTime(a: Animation, factor: Double): Animation {
        if (!factor.isFinite() || factor <= 0) fail("O fator de tempo deve ser maior que zero.")
        val scaled = mapAllKeys(a) { it.copy(time = quantize(it.time * factor)) }
        // dois quadros-chave podem colidir depois do arredondamento
        for (b in scaled.bones.values) for (k in ChannelKind.values()) {
            val ks = b.channel(k)?.keys ?: continue
            for (i in 1 until ks.size) if (sameTime(ks[i].time, ks[i - 1].time)) fail("O fator $factor faria dois quadros-chave caírem no mesmo tempo.")
        }
        return scaled.copy(length = a.length?.let { quantize(it * factor) })
    }

    /** Desloca todos os quadros-chave em [delta] segundos; tempos negativos são recusados. */
    fun shiftTime(a: Animation, delta: Double): Animation {
        val d = quantize(delta)
        var bad = false
        val out = mapAllKeys(a) { k -> quantize(k.time + d).also { if (it < 0) bad = true }.let { t -> k.copy(time = t) } }
        if (bad) fail("O deslocamento deixaria quadros-chave em tempo negativo.")
        return out
    }

    /** Inverte a animação no tempo (exige duração conhecida). */
    fun reverse(a: Animation): Animation {
        val len = a.duration
        if (len <= 0) fail("Defina a duração da animação para invertê-la.")
        return mapAllKeys(a) { k -> k.copy(time = quantize(len - k.time), pre = k.post, post = k.pre) }
    }

    /**
     * Copia a animação do osso [from] para [to] refletida (simetria esquerda/direita): rotação em Y e Z e posição em X trocam o sinal.
     * Expressões Molang são envolvidas em "-(...)" para trocar o sinal. Substitui o que [to] já tinha.
     */
    fun copyBoneMirrored(a: Animation, from: String, to: String): Animation {
        val src = a.bones[from] ?: fail("'$from' não está na animação.")
        checkBoneName(to)
        if (from == to) fail("Origem e destino são o mesmo osso.")
        fun neg(s: Scalar): Scalar = when (s) { is Scalar.Num -> Scalar.Num(-s.v); is Scalar.Expr -> Scalar.Expr("-(${s.src})") }
        fun flip(v: AVec, nx: Boolean, ny: Boolean, nz: Boolean) = AVec(if (nx) neg(v.x) else v.x, if (ny) neg(v.y) else v.y, if (nz) neg(v.z) else v.z)
        fun conv(ch: Channel?, nx: Boolean, ny: Boolean, nz: Boolean): Channel? = ch?.let {
            if (it.isConstant) Channel(constant = flip(it.constant!!, nx, ny, nz))
            else Channel(keys = it.keys.map { k -> k.copy(pre = flip(k.pre, nx, ny, nz), post = flip(k.post, nx, ny, nz)) })
        }
        val copy = BoneAnim(
            rotation = conv(src.rotation, false, true, true),
            position = conv(src.position, true, false, false),
            scale = src.scale,
        )
        val m = LinkedHashMap(a.bones)
        m[to] = copy
        return a.copy(bones = m)
    }
}

/** Linha da linha do tempo: um osso e uma propriedade, com os instantes dos quadros-chave. */
class TimelineRow(val bone: String, val kind: ChannelKind, val times: List<Double>, val constant: Boolean)

object Timeline {
    fun rows(a: Animation): List<TimelineRow> {
        val out = ArrayList<TimelineRow>()
        for ((bone, b) in a.bones) for (k in ChannelKind.values()) {
            val ch = b.channel(k) ?: continue
            out.add(TimelineRow(bone, k, ch.keys.map { it.time }, ch.isConstant))
        }
        return out
    }

    /** Arredonda [t] ao quadro mais próximo de [fps] (ex.: 20 fps = ticks do jogo; 30/60 para edição fina). */
    fun snap(t: Double, fps: Int): Double {
        require(fps > 0)
        return AnimationOps.quantize(Math.round(t * fps) / fps.toDouble())
    }

    /** Índice do quadro-chave mais próximo de [t] dentro de [tolerance] segundos, ou null. */
    fun nearestKey(times: List<Double>, t: Double, tolerance: Double): Int? {
        var best: Int? = null
        var bd = Double.MAX_VALUE
        times.forEachIndexed { i, x -> val d = abs(x - t); if (d <= tolerance && d < bd) { bd = d; best = i } }
        return best
    }
}

class AnimationEditor(initial: Animation, maxHistory: Int = 200) {
    private val history = History(initial, maxHistory)
    val animation: Animation get() = history.state
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    val undoLabel get() = history.undoLabel
    val redoLabel get() = history.redoLabel
    fun undo() = history.undo()
    fun redo() = history.redo()

    private fun edit(label: String, f: (Animation) -> Animation) = history.edit(label, f)

    fun setName(name: String) = edit("Renomear animação") { AnimationOps.setName(it, name) }
    fun setLoop(loop: Loop) = edit("Modo de repetição") { AnimationOps.setLoop(it, loop) }
    fun setLength(length: Double?, trim: Boolean = false) = edit("Duração") { AnimationOps.setLength(it, length, trim) }
    fun fitLength() = edit("Ajustar duração") { AnimationOps.fitLength(it) }
    fun setKeyframe(bone: String, kind: ChannelKind, time: Double, value: AVec, lerpMode: String? = null) =
        edit("Quadro-chave") { AnimationOps.setKeyframe(it, bone, kind, time, value, lerpMode) }
    fun setKeyframe(bone: String, kind: ChannelKind, time: Double, value: Vec3) = setKeyframe(bone, kind, time, AVec.of(value))
    fun deleteKeyframe(bone: String, kind: ChannelKind, index: Int) = edit("Excluir quadro-chave") { AnimationOps.deleteKeyframe(it, bone, kind, index) }
    fun moveKeyframe(bone: String, kind: ChannelKind, index: Int, newTime: Double) = edit("Mover quadro-chave") { AnimationOps.moveKeyframe(it, bone, kind, index, newTime) }
    fun setKeyframeValue(bone: String, kind: ChannelKind, index: Int, pre: AVec?, post: AVec) = edit("Valor do quadro-chave") { AnimationOps.setKeyframeValue(it, bone, kind, index, pre, post) }
    fun setKeyframeLerp(bone: String, kind: ChannelKind, index: Int, mode: String?) = edit("Interpolação") { AnimationOps.setKeyframeLerp(it, bone, kind, index, mode) }
    fun setConstant(bone: String, kind: ChannelKind, value: AVec) = edit("Valor constante") { AnimationOps.setConstant(it, bone, kind, value) }
    fun removeChannel(bone: String, kind: ChannelKind) = edit("Remover canal") { AnimationOps.removeChannel(it, bone, kind) }
    fun removeBone(bone: String) = edit("Remover osso da animação") { AnimationOps.removeBone(it, bone) }
    fun renameBone(old: String, new: String) = edit("Renomear osso") { AnimationOps.renameBone(it, old, new) }
    fun scaleTime(factor: Double) = edit("Escalar tempo") { AnimationOps.scaleTime(it, factor) }
    fun shiftTime(delta: Double) = edit("Deslocar tempo") { AnimationOps.shiftTime(it, delta) }
    fun reverse() = edit("Inverter") { AnimationOps.reverse(it) }
    fun copyBoneMirrored(from: String, to: String) = edit("Copiar espelhado") { AnimationOps.copyBoneMirrored(it, from, to) }
}
