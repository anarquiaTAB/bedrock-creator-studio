package studio.core.animation

import studio.core.geometry.BonePose
import studio.core.geometry.Vec3

object AnimSampler {
    /** Tempo dentro da animação conforme o modo de repetição. */
    fun wrapTime(a: Animation, t: Double): Double {
        val len = a.duration
        val x = if (t < 0) 0.0 else t
        if (len <= 0.0) return 0.0
        return when (a.loop) {
            Loop.LOOP -> x % len
            Loop.HOLD, Loop.ONCE -> minOf(x, len)
        }
    }

    private fun scalar(s: Scalar, ctx: MolangContext): Double = when (s) {
        is Scalar.Num -> s.v
        is Scalar.Expr -> try {
            Molang.parse(s.src).eval(ctx).let { if (it.isFinite()) it else 0.0 }
        } catch (e: MolangException) {
            ctx.unresolved.add("expressão inválida: ${s.src}")
            0.0
        }
    }

    fun eval(v: AVec, ctx: MolangContext) = Vec3(scalar(v.x, ctx), scalar(v.y, ctx), scalar(v.z, ctx))

    private fun lerp(a: Vec3, b: Vec3, f: Double) = a + (b - a) * f

    private fun catmull(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
        fun c(a: Double, b: Double, cc: Double, d: Double) =
            0.5 * ((2 * b) + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t * t + (-a + 3 * b - 3 * cc + d) * t * t * t)
        return Vec3(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z))
    }

    /**
     * Valor do canal no tempo [t] (já dentro da animação). Antes do primeiro quadro-chave vale o primeiro; depois do último, o último.
     * Catmull-Rom é usado no trecho se um dos dois quadros-chave do trecho pedir "catmullrom" (aproximação; confirmar no jogo).
     */
    fun sampleChannel(ch: Channel, t: Double, ctx: MolangContext): Vec3? {
        ch.constant?.let { return eval(it, ctx) }
        val k = ch.keys
        if (k.isEmpty()) return null
        if (t <= k.first().time) return eval(k.first().pre, ctx)
        if (t >= k.last().time) return eval(k.last().post, ctx)
        var i = 0
        while (i + 1 < k.size && k[i + 1].time <= t) i++
        val a = k[i]; val b = k[i + 1]
        val f = (t - a.time) / (b.time - a.time)
        val va = eval(a.post, ctx); val vb = eval(b.pre, ctx)
        if (!(a.smooth || b.smooth)) return lerp(va, vb, f)
        val p0 = if (i > 0) eval(k[i - 1].post, ctx) else va
        val p3 = if (i + 2 < k.size) eval(k[i + 2].pre, ctx) else vb
        return catmull(p0, va, vb, p3, f)
    }

    /**
     * Pose de todos os ossos animados no tempo [rawTime]. Os "query.anim_time" e "query.life_time" do [ctx] são atualizados;
     * quaisquer outros nomes não resolvidos ficam em [MolangContext.unresolved] e valem 0 (a interface deve avisar).
     */
    fun sample(a: Animation, rawTime: Double, ctx: MolangContext = MolangContext()): Map<String, BonePose> {
        val t = wrapTime(a, rawTime)
        ctx.queries["anim_time"] = t
        ctx.queries["life_time"] = rawTime
        val out = LinkedHashMap<String, BonePose>()
        for ((bone, ba) in a.bones) {
            val rot = ba.rotation?.let { sampleChannel(it, t, ctx) } ?: Vec3.ZERO
            val pos = ba.position?.let { sampleChannel(it, t, ctx) } ?: Vec3.ZERO
            val scl = ba.scale?.let { sampleChannel(it, t, ctx) } ?: Vec3.ONE
            out[bone] = BonePose(rot, pos, scl)
        }
        return out
    }
}

/** Controle de reprodução da prévia: tocar, pausar, reiniciar e avançar o tempo (chamado a cada quadro da tela). */
class AnimationPlayer(var animation: Animation) {
    /** Tempo decorrido bruto; use [AnimSampler.sample], que aplica o modo de repetição. */
    var elapsed: Double = 0.0
        private set
    var playing: Boolean = false
        private set
    var speed: Double = 1.0
        set(v) { require(v.isFinite() && v > 0) { "A velocidade deve ser positiva." }; field = v }

    /** true quando uma animação sem repetição chegou ao fim. */
    val finished: Boolean get() = animation.loop != Loop.LOOP && animation.duration > 0 && elapsed >= animation.duration

    val currentTime: Double get() = AnimSampler.wrapTime(animation, elapsed)

    fun play() {
        if (finished) elapsed = 0.0
        playing = true
    }

    fun pause() { playing = false }

    fun restart() { elapsed = 0.0; playing = true }

    fun stop() { elapsed = 0.0; playing = false }

    fun seek(t: Double) {
        require(t.isFinite()) { "Tempo inválido" }
        elapsed = t.coerceAtLeast(0.0).let { if (animation.loop == Loop.LOOP) it else minOf(it, animation.duration) }
    }

    /** Avança [dtSeconds] se estiver tocando. Devolve o tempo atual da animação. */
    fun tick(dtSeconds: Double): Double {
        if (playing && dtSeconds > 0) {
            elapsed += dtSeconds * speed
            if (animation.loop != Loop.LOOP && animation.duration > 0 && elapsed >= animation.duration) {
                elapsed = animation.duration
                playing = false
            }
            if (animation.loop == Loop.LOOP && animation.duration > 0) elapsed %= animation.duration
        }
        return currentTime
    }

    fun poses(ctx: MolangContext = MolangContext()) = AnimSampler.sample(animation, currentTime, ctx)
}
