package studio.core.animation

import studio.core.geometry.Vec3
import studio.core.json.JsonObj

/** Valor de um componente: número ou expressão Molang (guardada como texto, sem alteração). */
sealed interface Scalar {
    data class Num(val v: Double) : Scalar
    data class Expr(val src: String) : Scalar
}

data class AVec(val x: Scalar, val y: Scalar, val z: Scalar) {
    val isNumeric: Boolean get() = x is Scalar.Num && y is Scalar.Num && z is Scalar.Num

    /** Valor numérico, ou null se algum componente for expressão. */
    fun numbers(): Vec3? = if (isNumeric) Vec3((x as Scalar.Num).v, (y as Scalar.Num).v, (z as Scalar.Num).v) else null

    companion object {
        fun of(x: Double, y: Double, z: Double) = AVec(Scalar.Num(x), Scalar.Num(y), Scalar.Num(z))
        fun of(v: Vec3) = of(v.x, v.y, v.z)
        val ZERO = of(0.0, 0.0, 0.0)
    }
}

enum class ChannelKind(val key: String) {
    ROTATION("rotation"), POSITION("position"), SCALE("scale");

    companion object {
        fun fromKey(k: String) = values().firstOrNull { it.key == k }
    }
}

object LerpMode {
    const val LINEAR = "linear"
    const val CATMULLROM = "catmullrom"
}

/**
 * Quadro-chave. [pre] = valor chegando ao instante, [post] = valor saindo (iguais, a menos que haja salto).
 * [lerpMode] nulo = linear. [raw] guarda campos desconhecidos do objeto original.
 */
data class Keyframe(
    val time: Double,
    val pre: AVec,
    val post: AVec = pre,
    val lerpMode: String? = null,
    val raw: JsonObj? = null,
) {
    val split: Boolean get() = pre != post
    val smooth: Boolean get() = lerpMode == LerpMode.CATMULLROM
}

/** Canal de uma propriedade do osso: ou um valor constante (sem linha do tempo) ou uma lista de quadros-chave por tempo. */
data class Channel(val keys: List<Keyframe> = emptyList(), val constant: AVec? = null) {
    init {
        require(constant == null || keys.isEmpty()) { "Canal constante não tem quadros-chave." }
    }

    val isConstant: Boolean get() = constant != null
    val isEmpty: Boolean get() = constant == null && keys.isEmpty()
    val lastTime: Double get() = keys.maxOfOrNull { it.time } ?: 0.0
}

data class BoneAnim(
    val rotation: Channel? = null,
    val position: Channel? = null,
    val scale: Channel? = null,
    val raw: JsonObj = JsonObj.empty(),
) {
    fun channel(k: ChannelKind): Channel? = when (k) {
        ChannelKind.ROTATION -> rotation
        ChannelKind.POSITION -> position
        ChannelKind.SCALE -> scale
    }

    fun with(k: ChannelKind, c: Channel?): BoneAnim = when (k) {
        ChannelKind.ROTATION -> copy(rotation = c)
        ChannelKind.POSITION -> copy(position = c)
        ChannelKind.SCALE -> copy(scale = c)
    }

    val hasChannels: Boolean get() = rotation != null || position != null || scale != null
}

enum class Loop { ONCE, LOOP, HOLD }

data class Animation(
    val name: String,
    val loop: Loop = Loop.ONCE,
    val length: Double? = null,
    val bones: Map<String, BoneAnim> = emptyMap(),
    val raw: JsonObj = JsonObj.empty(),
) {
    /** Duração efetiva: animation_length, ou o último quadro-chave quando ausente. */
    val duration: Double
        get() = length ?: bones.values.maxOfOrNull { b -> ChannelKind.values().maxOfOrNull { k -> b.channel(k)?.lastTime ?: 0.0 } ?: 0.0 } ?: 0.0
}
