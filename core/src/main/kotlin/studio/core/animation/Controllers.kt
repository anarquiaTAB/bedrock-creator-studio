package studio.core.animation

import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity
import studio.core.geometry.GeometryFormatException
import studio.core.geometry.jnum
import studio.core.geometry.numOf
import studio.core.json.*
import studio.core.util.EditException
import studio.core.util.History

/** Animação dentro de um estado: nome curto (como declarado na entidade) e, opcionalmente, a expressão de mistura. */
data class StateAnim(val name: String, val blend: String? = null)

data class Transition(val target: String, val condition: String)

data class ControllerState(
    val name: String,
    val animations: List<StateAnim> = emptyList(),
    val transitions: List<Transition> = emptyList(),
    /** Tempo de mistura numérico; formas mais complexas ficam em [raw] e são preservadas. */
    val blendTransition: Double? = null,
    val raw: JsonObj = JsonObj.empty(),
)

data class Controller(
    val name: String,
    val initialState: String?,
    val states: List<ControllerState>,
    val raw: JsonObj = JsonObj.empty(),
) {
    fun state(n: String) = states.firstOrNull { it.name == n }

    /** Estados alcançáveis a partir do inicial (o inicial implícito é "default"). */
    fun reachable(): Set<String> {
        val start = initialState ?: "default"
        if (state(start) == null) return emptySet()
        val seen = LinkedHashSet<String>()
        val q = ArrayDeque<String>().apply { add(start) }
        while (q.isNotEmpty()) {
            val s = q.removeFirst()
            if (!seen.add(s)) continue
            state(s)?.transitions?.forEach { if (state(it.target) != null) q.add(it.target) }
        }
        return seen
    }
}

private fun err(code: String, msg: String, file: String?) = Diagnostic(Severity.ERROR, Category.STRUCTURE, code, msg, file)

object ControllerJson {
    const val DEFAULT_FORMAT_VERSION = "1.10.0"

    fun parse(name: String, o: JsonObj, file: String? = null): Pair<Controller?, List<Diagnostic>> {
        val d = ArrayList<Diagnostic>()
        val states = ArrayList<ControllerState>()
        val sv = o["states"]
        if (sv != null && sv !is JsonObj) d += err("CTRL_STATES", "'$name': \"states\" deve ser um objeto.", file)
        (sv as? JsonObj)?.fields?.forEach { (sn, v) ->
            val so = v as? JsonObj
            if (so == null) { d += err("CTRL_STATE", "'$name': o estado '$sn' deve ser um objeto.", file); return@forEach }
            val anims = ArrayList<StateAnim>()
            val av = so["animations"]
            if (av != null && av !is JsonArr) d += err("CTRL_ANIMS", "'$name/$sn': \"animations\" deve ser uma lista.", file)
            (av as? JsonArr)?.items?.forEach { it0 ->
                when (it0) {
                    is JsonStr -> anims.add(StateAnim(it0.value))
                    is JsonObj -> it0.fields.forEach { (an, bv) ->
                        val blend = (bv as? JsonStr)?.value ?: (bv as? JsonNum)?.raw
                        if (blend == null) d += err("CTRL_ANIMS", "'$name/$sn': a mistura de '$an' deve ser texto (Molang) ou número.", file)
                        else anims.add(StateAnim(an, blend))
                    }
                    else -> d += err("CTRL_ANIMS", "'$name/$sn': item de \"animations\" inválido.", file)
                }
            }
            val trs = ArrayList<Transition>()
            val tv = so["transitions"]
            if (tv != null && tv !is JsonArr) d += err("CTRL_TRANS", "'$name/$sn': \"transitions\" deve ser uma lista.", file)
            (tv as? JsonArr)?.items?.forEach { t ->
                val to = t as? JsonObj
                if (to == null) d += err("CTRL_TRANS", "'$name/$sn': transição inválida (use {\"estado\": \"condição\"}).", file)
                else to.fields.forEach { (target, cv) ->
                    val c = (cv as? JsonStr)?.value ?: (cv as? JsonNum)?.raw
                    if (c == null) d += err("CTRL_TRANS", "'$name/$sn': a condição para '$target' deve ser texto.", file) else trs.add(Transition(target, c))
                }
            }
            states.add(ControllerState(sn, anims, trs, numOf(so["blend_transition"]), so))
        }
        val initial = o["initial_state"]
        if (initial != null && initial !is JsonStr) d += err("CTRL_INITIAL", "'$name': initial_state deve ser texto.", file)
        if (d.any { it.severity == Severity.ERROR }) return null to d
        return Controller(name, (initial as? JsonStr)?.value, states, o) to d
    }

    fun toJson(c: Controller): JsonObj {
        var o = c.raw
        o = if (c.initialState != null) o.with("initial_state", JsonStr(c.initialState)) else o.without("initial_state")
        val states = LinkedHashMap<String, JsonValue>()
        for (s in c.states) {
            var so = s.raw
            so = if (s.animations.isEmpty() && so["animations"] == null) so else so.with("animations", JsonArr(s.animations.map { a ->
                if (a.blend == null) JsonStr(a.name) else jsonObj(a.name to (a.blend.toDoubleOrNull()?.let { jnum(it) } ?: JsonStr(a.blend)))
            }))
            so = if (s.transitions.isEmpty() && so["transitions"] == null) so else so.with("transitions", JsonArr(s.transitions.map { jsonObj(it.target to JsonStr(it.condition)) }))
            so = when {
                s.blendTransition != null -> so.with("blend_transition", jnum(s.blendTransition))
                so["blend_transition"] is JsonNum -> so.without("blend_transition") // era numérico e foi removido
                else -> so
            }
            states[s.name] = so
        }
        return o.with("states", JsonObj(states))
    }
}

class ControllerFile private constructor(val root: JsonObj) {
    class Entry(val name: String, val controller: Controller?, val diagnostics: List<Diagnostic>)

    private fun ctrls(): JsonObj = root.obj("animation_controllers") ?: JsonObj.empty()
    fun names(): List<String> = ctrls().fields.keys.toList()

    fun entries(file: String? = null): List<Entry> = ctrls().fields.map { (n, v) ->
        val o = v as? JsonObj
        if (o == null) Entry(n, null, listOf(err("CTRL_NOT_OBJECT", "O controlador '$n' deve ser um objeto.", file)))
        else ControllerJson.parse(n, o, file).let { (c, d) -> Entry(n, c, d) }
    }

    fun find(name: String, file: String? = null) = entries(file).firstOrNull { it.name == name }

    private fun with(m: Map<String, JsonValue>) = ControllerFile(root.with("animation_controllers", JsonObj(LinkedHashMap(m))))

    fun replace(c: Controller, previousName: String = c.name): ControllerFile {
        val cur = ctrls().fields
        if (previousName !in cur) throw GeometryFormatException("Controlador '$previousName' não encontrado.")
        if (c.name != previousName && c.name in cur) throw GeometryFormatException("Já existe o controlador '${c.name}'.")
        val out = LinkedHashMap<String, JsonValue>()
        for ((k, v) in cur) if (k == previousName) out[c.name] = ControllerJson.toJson(c) else out[k] = v
        return with(out)
    }

    fun add(c: Controller): ControllerFile {
        val cur = ctrls().fields
        if (c.name in cur) throw GeometryFormatException("Já existe o controlador '${c.name}'.")
        return with(LinkedHashMap(cur).also { it[c.name] = ControllerJson.toJson(c) })
    }

    fun remove(name: String): ControllerFile {
        val cur = ctrls().fields
        if (name !in cur) throw GeometryFormatException("Controlador '$name' não encontrado.")
        return with(LinkedHashMap(cur).also { it.remove(name) })
    }

    fun toText() = Json.write(root) + "\n"

    companion object {
        fun parse(text: String): ControllerFile {
            val o = try { Json.parseObject(text) } catch (e: JsonParseException) { throw GeometryFormatException("JSON inválido: ${e.message}") }
            return from(o)
        }

        fun from(root: JsonObj): ControllerFile {
            if (root.obj("animation_controllers") == null) throw GeometryFormatException("O arquivo não tem o objeto \"animation_controllers\".")
            return ControllerFile(root)
        }

        fun create(c: Controller, version: String = ControllerJson.DEFAULT_FORMAT_VERSION) =
            ControllerFile(jsonObj("format_version" to JsonStr(version), "animation_controllers" to jsonObj(c.name to ControllerJson.toJson(c))))
    }
}

object ControllerOps {
    private fun fail(msg: String): Nothing = throw EditException(msg)

    fun checkName(name: String) {
        if (!name.startsWith("controller.animation.") || name.length <= "controller.animation.".length || name.any { it.isWhitespace() }) {
            fail("O nome do controlador deve começar com 'controller.animation.' e não ter espaços.")
        }
    }

    private fun need(c: Controller, s: String) = c.state(s) ?: fail("O estado '$s' não existe.")

    private fun mapState(c: Controller, s: String, f: (ControllerState) -> ControllerState): Controller {
        need(c, s)
        return c.copy(states = c.states.map { if (it.name == s) f(it) else it })
    }

    fun setName(c: Controller, name: String): Controller { checkName(name); return c.copy(name = name) }

    fun addState(c: Controller, name: String): Controller {
        if (name.isBlank() || name != name.trim()) fail("Nome de estado inválido.")
        if (c.state(name) != null) fail("Já existe o estado '$name'.")
        val first = c.states.isEmpty()
        val next = c.copy(states = c.states + ControllerState(name))
        return if (first && c.initialState == null && name != "default") next.copy(initialState = name) else next
    }

    /** Remove o estado. As transições que apontavam para ele também são removidas; o estado inicial passa para outro se necessário. */
    fun removeState(c: Controller, name: String): Controller {
        need(c, name)
        val rest = c.states.filter { it.name != name }.map { s -> s.copy(transitions = s.transitions.filter { it.target != name }) }
        val initial = if (c.initialState == name || (c.initialState == null && name == "default")) rest.firstOrNull()?.name else c.initialState
        return c.copy(states = rest, initialState = initial)
    }

    fun renameState(c: Controller, old: String, new: String): Controller {
        need(c, old)
        if (old == new) return c
        if (new.isBlank() || new != new.trim()) fail("Nome de estado inválido.")
        if (c.state(new) != null) fail("Já existe o estado '$new'.")
        return c.copy(
            initialState = if (c.initialState == old) new else c.initialState,
            states = c.states.map { s ->
                val r = if (s.name == old) s.copy(name = new) else s
                r.copy(transitions = r.transitions.map { if (it.target == old) it.copy(target = new) else it })
            },
        )
    }

    fun setInitial(c: Controller, name: String?): Controller {
        if (name != null) need(c, name)
        return c.copy(initialState = name)
    }

    fun addTransition(c: Controller, from: String, target: String, condition: String): Controller {
        need(c, target)
        if (condition.isBlank()) fail("A condição não pode ficar vazia.")
        Molang.check(condition)?.let { if (!it.unsupported) fail("Condição inválida: ${it.message}") }
        return mapState(c, from) { it.copy(transitions = it.transitions + Transition(target, condition)) }
    }

    fun removeTransition(c: Controller, from: String, index: Int): Controller {
        val s = need(c, from)
        if (index !in s.transitions.indices) fail("A transição $index não existe.")
        return mapState(c, from) { it.copy(transitions = it.transitions.filterIndexed { i, _ -> i != index }) }
    }

    fun setTransitionCondition(c: Controller, from: String, index: Int, condition: String): Controller {
        val s = need(c, from)
        if (index !in s.transitions.indices) fail("A transição $index não existe.")
        Molang.check(condition)?.let { if (!it.unsupported) fail("Condição inválida: ${it.message}") }
        return mapState(c, from) { it.copy(transitions = it.transitions.mapIndexed { i, t -> if (i == index) t.copy(condition = condition) else t }) }
    }

    fun setAnimations(c: Controller, state: String, anims: List<StateAnim>): Controller {
        for (a in anims) {
            if (a.name.isBlank()) fail("Nome de animação vazio.")
            a.blend?.let { b -> Molang.check(b)?.let { e -> if (!e.unsupported) fail("Mistura de '${a.name}' inválida: ${e.message}") } }
        }
        return mapState(c, state) { it.copy(animations = anims) }
    }

    fun setBlendTransition(c: Controller, state: String, seconds: Double?): Controller {
        if (seconds != null && (!seconds.isFinite() || seconds < 0)) fail("O tempo de mistura não pode ser negativo.")
        return mapState(c, state) { it.copy(blendTransition = seconds) }
    }
}

class ControllerEditor(initial: Controller, maxHistory: Int = 200) {
    private val history = History(initial, maxHistory)
    val controller: Controller get() = history.state
    val canUndo get() = history.canUndo
    val canRedo get() = history.canRedo
    fun undo() = history.undo()
    fun redo() = history.redo()
    private fun edit(label: String, f: (Controller) -> Controller) = history.edit(label, f)

    fun setName(n: String) = edit("Renomear controlador") { ControllerOps.setName(it, n) }
    fun addState(n: String) = edit("Adicionar estado") { ControllerOps.addState(it, n) }
    fun removeState(n: String) = edit("Remover estado") { ControllerOps.removeState(it, n) }
    fun renameState(o: String, n: String) = edit("Renomear estado") { ControllerOps.renameState(it, o, n) }
    fun setInitial(n: String?) = edit("Estado inicial") { ControllerOps.setInitial(it, n) }
    fun addTransition(from: String, target: String, cond: String) = edit("Adicionar transição") { ControllerOps.addTransition(it, from, target, cond) }
    fun removeTransition(from: String, i: Int) = edit("Remover transição") { ControllerOps.removeTransition(it, from, i) }
    fun setTransitionCondition(from: String, i: Int, cond: String) = edit("Condição da transição") { ControllerOps.setTransitionCondition(it, from, i, cond) }
    fun setAnimations(state: String, anims: List<StateAnim>) = edit("Animações do estado") { ControllerOps.setAnimations(it, state, anims) }
    fun setBlendTransition(state: String, s: Double?) = edit("Mistura do estado") { ControllerOps.setBlendTransition(it, state, s) }
}
