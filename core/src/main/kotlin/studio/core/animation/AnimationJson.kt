package studio.core.animation

import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity
import studio.core.geometry.GeometryFormatException
import studio.core.geometry.jnum
import studio.core.geometry.numOf
import studio.core.json.*
import studio.core.util.Num

private fun err(code: String, msg: String, file: String?) = Diagnostic(Severity.ERROR, Category.STRUCTURE, code, msg, file)

object AnimationJson {
    /** Padrão para arquivos novos. CONFERIR com a documentação do jogo na versão-alvo. */
    const val DEFAULT_FORMAT_VERSION = "1.8.0"
    private val KEY_FIELDS = setOf("pre", "post", "lerp_mode")

    private fun scalar(v: JsonValue?): Scalar? = when (v) {
        is JsonNum -> numOf(v)?.let { Scalar.Num(it) }
        is JsonStr -> Scalar.Expr(v.value)
        else -> null
    }

    /** [x, y, z] (números ou Molang) ou um único valor repetido nos três eixos. */
    private fun avec(v: JsonValue?): AVec? = when (v) {
        is JsonArr -> if (v.items.size == 3) {
            val s = v.items.map { scalar(it) ?: return null }
            AVec(s[0], s[1], s[2])
        } else null
        is JsonNum, is JsonStr -> scalar(v)?.let { AVec(it, it, it) }
        else -> null
    }

    fun parse(name: String, o: JsonObj, file: String? = null): Pair<Animation?, List<Diagnostic>> {
        val d = ArrayList<Diagnostic>()
        val loop = when (val l = o["loop"]) {
            null -> Loop.ONCE
            is JsonBool -> if (l.value) Loop.LOOP else Loop.ONCE
            is JsonStr -> if (l.value == "hold_on_last_frame") Loop.HOLD else { d += err("ANIM_LOOP", "'$name': loop deve ser true, false ou \"hold_on_last_frame\".", file); Loop.ONCE }
            else -> { d += err("ANIM_LOOP", "'$name': loop deve ser true, false ou \"hold_on_last_frame\".", file); Loop.ONCE }
        }
        val len = o["animation_length"]?.let { numOf(it) ?: run { d += err("ANIM_LENGTH", "'$name': animation_length deve ser um número.", file); null } }
        val bones = LinkedHashMap<String, BoneAnim>()
        val bv = o["bones"]
        if (bv != null && bv !is JsonObj) d += err("ANIM_BONES", "'$name': \"bones\" deve ser um objeto.", file)
        (bv as? JsonObj)?.fields?.forEach { (bn, v) ->
            val bo = v as? JsonObj
            if (bo == null) { d += err("ANIM_BONE", "'$name': o osso '$bn' deve ser um objeto.", file); return@forEach }
            var ba = BoneAnim(raw = bo)
            for (k in ChannelKind.values()) {
                val cv = bo[k.key] ?: continue
                ba = ba.with(k, parseChannel(cv, "$name/$bn/${k.key}", d, file))
            }
            bones[bn] = ba
        }
        if (d.any { it.severity == Severity.ERROR }) return null to d
        return Animation(name, loop, len, bones, o) to d
    }

    private fun parseChannel(v: JsonValue, where: String, d: MutableList<Diagnostic>, file: String?): Channel? {
        if (v !is JsonObj) {
            val c = avec(v)
            if (c == null) { d += err("ANIM_VALUE", "$where: valor inválido (use [x, y, z] ou número/Molang).", file); return null }
            return Channel(constant = c)
        }
        val keys = ArrayList<Keyframe>()
        for ((ts, kv) in v.fields) {
            val t = ts.toDoubleOrNull()?.takeIf { it.isFinite() }
            if (t == null) { d += err("ANIM_TIME", "$where: '$ts' não é um tempo válido.", file); continue }
            if (kv is JsonObj) {
                val post = kv["post"]?.let { avec(it) }
                val pre = kv["pre"]?.let { avec(it) }
                if ((kv["post"] != null && post == null) || (kv["pre"] != null && pre == null) || (post == null && pre == null)) {
                    d += err("ANIM_VALUE", "$where/$ts: precisa de \"post\" (e opcionalmente \"pre\") com [x, y, z].", file); continue
                }
                val lm = (kv["lerp_mode"] as? JsonStr)?.value
                keys.add(Keyframe(t, pre ?: post!!, post ?: pre!!, lm?.takeIf { it != LerpMode.LINEAR }, kv))
            } else {
                val a = avec(kv)
                if (a == null) { d += err("ANIM_VALUE", "$where/$ts: valor inválido.", file); continue }
                keys.add(Keyframe(t, a))
            }
        }
        val sorted = keys.sortedBy { it.time }
        for (i in 1 until sorted.size) {
            if (Num.fmtTime(sorted[i].time) == Num.fmtTime(sorted[i - 1].time)) {
                d += Diagnostic(Severity.WARNING, Category.STRUCTURE, "ANIM_TIME_DUP", "$where: dois quadros-chave no mesmo tempo (${Num.fmtTime(sorted[i].time)}).", file)
            }
        }
        return Channel(keys = sorted)
    }

    private fun scalarJson(s: Scalar): JsonValue = when (s) {
        is Scalar.Num -> jnum(s.v)
        is Scalar.Expr -> JsonStr(s.src)
    }

    private fun avecJson(a: AVec) = JsonArr(listOf(scalarJson(a.x), scalarJson(a.y), scalarJson(a.z)))

    private fun keyJson(k: Keyframe): JsonValue {
        val extra = k.raw?.fields?.keys?.any { it !in KEY_FIELDS } == true
        val smooth = k.lerpMode != null
        if (!k.split && !smooth && !extra) return avecJson(k.post)
        var o = k.raw ?: JsonObj.empty()
        o = if (k.split) o.with("pre", avecJson(k.pre)) else o.without("pre")
        o = o.with("post", avecJson(k.post))
        o = if (k.lerpMode != null) o.with("lerp_mode", JsonStr(k.lerpMode)) else o.without("lerp_mode")
        return o
    }

    private fun channelJson(c: Channel): JsonValue {
        c.constant?.let { return avecJson(it) }
        val fields = LinkedHashMap<String, JsonValue>()
        for (k in c.keys.sortedBy { it.time }) {
            val ts = Num.fmtTime(k.time)
            if (fields.containsKey(ts)) throw GeometryFormatException("Dois quadros-chave no mesmo tempo ($ts).")
            fields[ts] = keyJson(k)
        }
        return JsonObj(fields)
    }

    fun toJson(a: Animation): JsonObj {
        var o = a.raw
        o = when (a.loop) {
            Loop.LOOP -> o.with("loop", JsonBool(true))
            Loop.HOLD -> o.with("loop", JsonStr("hold_on_last_frame"))
            Loop.ONCE -> if ((o["loop"] as? JsonBool)?.value == false) o else o.without("loop")
        }
        o = if (a.length != null) o.with("animation_length", jnum(a.length)) else o.without("animation_length")
        if (a.bones.isNotEmpty() || o["bones"] != null) {
            val bones = LinkedHashMap<String, JsonValue>()
            for ((bn, b) in a.bones) {
                var bo = b.raw
                for (k in ChannelKind.values()) {
                    val c = b.channel(k)
                    bo = if (c != null && !c.isEmpty) bo.with(k.key, channelJson(c)) else bo.without(k.key)
                }
                bones[bn] = bo
            }
            o = o.with("bones", JsonObj(bones))
        }
        return o
    }
}

/** Arquivo de animações (`*.animation.json`). Preserva as animações que a interface não consegue ler. */
class AnimationFile private constructor(val root: JsonObj) {
    class Entry(val name: String, val animation: Animation?, val diagnostics: List<Diagnostic>)

    val formatVersion: String? get() = root.str("format_version")
    private fun anims(): JsonObj = root.obj("animations") ?: JsonObj.empty()

    fun names(): List<String> = anims().fields.keys.toList()

    fun entries(file: String? = null): List<Entry> = anims().fields.map { (n, v) ->
        val o = v as? JsonObj
        if (o == null) Entry(n, null, listOf(err("ANIM_NOT_OBJECT", "A animação '$n' deve ser um objeto.", file)))
        else AnimationJson.parse(n, o, file).let { (a, d) -> Entry(n, a, d) }
    }

    fun find(name: String, file: String? = null): Entry? = entries(file).firstOrNull { it.name == name }

    private fun with(map: Map<String, JsonValue>) = AnimationFile(root.with("animations", JsonObj(LinkedHashMap(map))))

    /** Substitui a animação ([previousName] permite renomear mantendo a posição). As demais ficam intactas. */
    fun replace(a: Animation, previousName: String = a.name): AnimationFile {
        val cur = anims().fields
        if (previousName !in cur) throw GeometryFormatException("Animação '$previousName' não encontrada.")
        if (a.name != previousName && a.name in cur) throw GeometryFormatException("Já existe uma animação '${a.name}'.")
        val out = LinkedHashMap<String, JsonValue>()
        for ((k, v) in cur) if (k == previousName) out[a.name] = AnimationJson.toJson(a) else out[k] = v
        return with(out)
    }

    fun add(a: Animation): AnimationFile {
        val cur = anims().fields
        if (a.name in cur) throw GeometryFormatException("Já existe uma animação '${a.name}'.")
        return with(LinkedHashMap(cur).also { it[a.name] = AnimationJson.toJson(a) })
    }

    fun remove(name: String): AnimationFile {
        val cur = anims().fields
        if (name !in cur) throw GeometryFormatException("Animação '$name' não encontrada.")
        return with(LinkedHashMap(cur).also { it.remove(name) })
    }

    fun toText(): String = Json.write(root) + "\n"

    companion object {
        fun parse(text: String): AnimationFile {
            val o = try { Json.parseObject(text) } catch (e: JsonParseException) { throw GeometryFormatException("JSON inválido: ${e.message}") }
            return from(o)
        }

        fun from(root: JsonObj): AnimationFile {
            if (root.obj("animations") == null) throw GeometryFormatException("O arquivo não tem o objeto \"animations\".")
            return AnimationFile(root)
        }

        fun create(a: Animation, version: String = AnimationJson.DEFAULT_FORMAT_VERSION): AnimationFile =
            AnimationFile(jsonObj("format_version" to JsonStr(version), "animations" to jsonObj(a.name to AnimationJson.toJson(a))))
    }
}
