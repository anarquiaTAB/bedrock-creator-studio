package studio.core.animation

import kotlin.math.abs

/** [unsupported] = a sintaxe pode ser válida no jogo, mas este avaliador de prévia não a entende (não é erro do usuário). */
class MolangException(message: String, val position: Int, val unsupported: Boolean = false) : Exception(message)

class MolangContext(
    /** Valores de "query.*" informados pela interface (ex.: anim_time, life_time, ground_speed). Chaves sem o prefixo. */
    val queries: MutableMap<String, Double> = HashMap(),
    val variables: MutableMap<String, Double> = HashMap(),
    val random: java.util.Random = java.util.Random(1),
) {
    /** Nomes que a prévia não soube resolver (valem 0). A interface pode oferecer controles para eles. */
    val unresolved: MutableSet<String> = LinkedHashSet()
}

sealed class MolangNode {
    class Num(val v: Double) : MolangNode()
    class Str(val v: String) : MolangNode()
    class Name(val path: String) : MolangNode()
    class Call(val name: String, val args: List<MolangNode>) : MolangNode()
    class Unary(val op: Char, val e: MolangNode) : MolangNode()
    class Bin(val op: String, val l: MolangNode, val r: MolangNode) : MolangNode()
    class Cond(val c: MolangNode, val a: MolangNode?, val b: MolangNode?) : MolangNode()
    class Assign(val name: String, val e: MolangNode) : MolangNode()
    class Return(val e: MolangNode) : MolangNode()
    class Block(val stmts: List<MolangNode>) : MolangNode()
}

/**
 * Subconjunto de Molang suficiente para a maioria das animações: números, aritmética, comparações, lógica, ternário, `??`,
 * atribuição de variáveis, `return`, funções `math.*` e `query.*`/`variable.*`/`temp.*`. Ângulos de sin/cos usam GRAUS, como no Molang.
 * O que não é suportado (arrays, loops, structs, funções de query) é sinalizado, nunca adivinhado.
 */
class MolangExpr internal constructor(val source: String, private val root: MolangNode) {
    /** Nomes "query.xxx" usados (sem o prefixo). */
    val queryNames: Set<String>
    val variableNames: Set<String>

    init {
        val q = LinkedHashSet<String>(); val v = LinkedHashSet<String>()
        fun walk(n: MolangNode) {
            when (n) {
                is MolangNode.Name -> when {
                    n.path.startsWith("query.") -> q.add(n.path.removePrefix("query."))
                    n.path.startsWith("variable.") -> v.add(n.path.removePrefix("variable."))
                }
                is MolangNode.Call -> { if (n.name.startsWith("query.")) q.add(n.name.removePrefix("query.")); n.args.forEach(::walk) }
                is MolangNode.Unary -> walk(n.e)
                is MolangNode.Bin -> { walk(n.l); walk(n.r) }
                is MolangNode.Cond -> { walk(n.c); n.a?.let(::walk); n.b?.let(::walk) }
                is MolangNode.Assign -> { v.add(n.name.removePrefix("variable.")); walk(n.e) }
                is MolangNode.Return -> walk(n.e)
                is MolangNode.Block -> n.stmts.forEach(::walk)
                else -> {}
            }
        }
        walk(root)
        queryNames = q; variableNames = v
    }

    fun eval(ctx: MolangContext): Double = Eval(ctx).run(root)

    private class ReturnSignal(val value: Double) : RuntimeException(null, null, false, false)

    private class Eval(val ctx: MolangContext) {
        fun bool(b: Boolean) = if (b) 1.0 else 0.0

        fun run(n: MolangNode): Double = try { ev(n) } catch (r: ReturnSignal) { r.value }

        fun ev(n: MolangNode): Double = when (n) {
            is MolangNode.Num -> n.v
            is MolangNode.Str -> { ctx.unresolved.add("'${n.v}'"); 0.0 }
            is MolangNode.Name -> name(n.path)
            is MolangNode.Call -> call(n)
            is MolangNode.Unary -> {
                val x = ev(n.e)
                when (n.op) { '-' -> -x; '!' -> bool(x == 0.0); else -> x }
            }
            is MolangNode.Bin -> bin(n)
            is MolangNode.Cond -> if (ev(n.c) != 0.0) (n.a?.let { ev(it) } ?: 0.0) else (n.b?.let { ev(it) } ?: 0.0)
            is MolangNode.Assign -> ev(n.e).also { ctx.variables[n.name] = it }
            is MolangNode.Return -> throw ReturnSignal(ev(n.e))
            is MolangNode.Block -> { var last = 0.0; for (s in n.stmts) last = ev(s); last }
        }

        fun name(p: String): Double = when {
            p == "true" -> 1.0
            p == "false" -> 0.0
            p == "math.pi" -> Math.PI
            p.startsWith("query.") -> ctx.queries[p.removePrefix("query.")] ?: run { ctx.unresolved.add(p); 0.0 }
            else -> ctx.variables[p] ?: 0.0 // variável não definida vale 0 no Molang
        }

        fun bin(n: MolangNode.Bin): Double {
            if (n.op == "&&") { val l = ev(n.l); return if (l == 0.0) 0.0 else bool(ev(n.r) != 0.0) }
            if (n.op == "||") { val l = ev(n.l); return if (l != 0.0) 1.0 else bool(ev(n.r) != 0.0) }
            if (n.op == "??") {
                // "nulo" na prévia = nome não resolvido
                if (n.l is MolangNode.Name && n.l.path.startsWith("query.") && ctx.queries[n.l.path.removePrefix("query.")] == null) return ev(n.r)
                return ev(n.l)
            }
            val a = ev(n.l); val b = ev(n.r)
            return when (n.op) {
                "+" -> a + b; "-" -> a - b; "*" -> a * b
                "/" -> if (b == 0.0) 0.0 else a / b // Molang: divisão por zero dá 0
                "<" -> bool(a < b); "<=" -> bool(a <= b); ">" -> bool(a > b); ">=" -> bool(a >= b)
                "==" -> bool(abs(a - b) < 1e-9); "!=" -> bool(abs(a - b) >= 1e-9)
                else -> 0.0
            }
        }

        fun call(c: MolangNode.Call): Double {
            if (!c.name.startsWith("math.")) { ctx.unresolved.add(c.name + "()"); c.args.forEach { ev(it) }; return 0.0 }
            val a = c.args.map { ev(it) }
            return when (c.name.removePrefix("math.")) {
                "abs" -> abs(a[0]); "sin" -> Math.sin(Math.toRadians(a[0])); "cos" -> Math.cos(Math.toRadians(a[0]))
                "asin" -> Math.toDegrees(Math.asin(a[0])); "acos" -> Math.toDegrees(Math.acos(a[0])); "atan" -> Math.toDegrees(Math.atan(a[0]))
                "atan2" -> Math.toDegrees(Math.atan2(a[0], a[1]))
                "sqrt" -> if (a[0] < 0) 0.0 else Math.sqrt(a[0])
                "pow" -> Math.pow(a[0], a[1]).let { if (it.isFinite()) it else 0.0 }
                "exp" -> Math.exp(a[0]); "ln" -> if (a[0] <= 0) 0.0 else Math.log(a[0])
                "floor" -> Math.floor(a[0]); "ceil" -> Math.ceil(a[0])
                "round" -> if (a[0] >= 0) Math.floor(a[0] + 0.5) else -Math.floor(-a[0] + 0.5)
                "trunc" -> if (a[0] < 0) Math.ceil(a[0]) else Math.floor(a[0])
                "clamp" -> a[0].coerceIn(minOf(a[1], a[2]), maxOf(a[1], a[2]))
                "min" -> minOf(a[0], a[1]); "max" -> maxOf(a[0], a[1])
                "mod" -> if (a[1] == 0.0) 0.0 else a[0] % a[1]
                "lerp" -> a[0] + (a[1] - a[0]) * a[2]
                "sign" -> Math.signum(a[0])
                "random" -> a[0] + ctx.random.nextDouble() * (a[1] - a[0])
                "hermite_blend" -> 3 * a[0] * a[0] - 2 * a[0] * a[0] * a[0]
                else -> 0.0
            }
        }
    }
}

object Molang {
    private val ARITY = mapOf(
        "abs" to 1, "sin" to 1, "cos" to 1, "asin" to 1, "acos" to 1, "atan" to 1, "atan2" to 2, "sqrt" to 1, "pow" to 2,
        "exp" to 1, "ln" to 1, "floor" to 1, "ceil" to 1, "round" to 1, "trunc" to 1, "clamp" to 3, "min" to 2, "max" to 2,
        "mod" to 2, "lerp" to 3, "sign" to 1, "random" to 2, "hermite_blend" to 1,
    )
    private val cache = object : LinkedHashMap<String, MolangExpr>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, MolangExpr>?) = size > 512
    }

    fun parse(src: String): MolangExpr = synchronized(cache) { cache[src] } ?: Parser(src).parse().also { synchronized(cache) { cache[src] = it } }

    /** Valida sem avaliar; null se a expressão é aceita. */
    fun check(src: String): MolangException? = try { parse(src); null } catch (e: MolangException) { e }

    private fun normalize(name: String): String {
        val n = name.lowercase()
        return when {
            n.startsWith("q.") -> "query." + n.substring(2)
            n.startsWith("v.") -> "variable." + n.substring(2)
            n.startsWith("t.") -> "temp." + n.substring(2)
            n.startsWith("c.") -> "context." + n.substring(2)
            else -> n
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(msg: String, unsupported: Boolean = false): Nothing = throw MolangException(msg, i, unsupported)

        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun parse(): MolangExpr {
            val stmts = ArrayList<MolangNode>()
            ws()
            if (i >= s.length) fail("Expressão vazia.")
            while (true) {
                ws()
                if (i >= s.length) break
                stmts.add(statement())
                ws()
                if (i < s.length && s[i] == ';') { i++; continue }
                if (i < s.length) fail("Caractere inesperado '${s[i]}'.")
            }
            return MolangExpr(s, if (stmts.size == 1) stmts[0] else MolangNode.Block(stmts))
        }

        fun peekWord(): String? {
            ws()
            var j = i
            while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '.')) j++
            return if (j > i) s.substring(i, j) else null
        }

        fun statement(): MolangNode {
            val w = peekWord()
            if (w != null && w.lowercase() == "return") {
                i += w.length
                return MolangNode.Return(expr())
            }
            if (w != null && w.lowercase() in setOf("loop", "for_each", "break", "continue")) fail("'$w' não é suportado na prévia.", unsupported = true)
            if (w != null) {
                val save = i
                i += w.length
                ws()
                if (i < s.length && s[i] == '=' && (i + 1 >= s.length || s[i + 1] != '=')) {
                    val n = normalize(w)
                    if (!(n.startsWith("variable.") || n.startsWith("temp.") || n.startsWith("context."))) fail("Só é possível atribuir a variable.*, temp.* ou context.*.")
                    i++
                    return MolangNode.Assign(n, expr())
                }
                i = save
            }
            return expr()
        }

        fun expr(): MolangNode {
            val c = coalesce()
            ws()
            if (i < s.length && s[i] == '?' && (i + 1 >= s.length || s[i + 1] != '?')) {
                i++
                val a = expr()
                ws()
                if (i < s.length && s[i] == ':') {
                    i++
                    return MolangNode.Cond(c, a, expr())
                }
                return MolangNode.Cond(c, a, null) // "cond ? valor" sem else vale 0 quando falso
            }
            return c
        }

        fun coalesce(): MolangNode {
            var l = or()
            while (true) {
                ws()
                if (s.startsWith("??", i)) { i += 2; l = MolangNode.Bin("??", l, or()) } else return l
            }
        }

        fun or(): MolangNode {
            var l = and()
            while (true) { ws(); if (s.startsWith("||", i)) { i += 2; l = MolangNode.Bin("||", l, and()) } else return l }
        }

        fun and(): MolangNode {
            var l = equality()
            while (true) { ws(); if (s.startsWith("&&", i)) { i += 2; l = MolangNode.Bin("&&", l, equality()) } else return l }
        }

        fun equality(): MolangNode {
            var l = relational()
            while (true) {
                ws()
                val op = when { s.startsWith("==", i) -> "=="; s.startsWith("!=", i) -> "!="; else -> return l }
                i += 2
                l = MolangNode.Bin(op, l, relational())
            }
        }

        fun relational(): MolangNode {
            var l = additive()
            while (true) {
                ws()
                val op = when {
                    s.startsWith("<=", i) -> "<="; s.startsWith(">=", i) -> ">="
                    s.startsWith("<", i) -> "<"; s.startsWith(">", i) -> ">"
                    else -> return l
                }
                i += op.length
                l = MolangNode.Bin(op, l, additive())
            }
        }

        fun additive(): MolangNode {
            var l = term()
            while (true) {
                ws()
                if (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i].toString(); i++; l = MolangNode.Bin(op, l, term()) } else return l
            }
        }

        fun term(): MolangNode {
            var l = unary()
            while (true) {
                ws()
                if (i < s.length && (s[i] == '*' || s[i] == '/')) { val op = s[i].toString(); i++; l = MolangNode.Bin(op, l, unary()) } else return l
            }
        }

        fun unary(): MolangNode {
            ws()
            if (i < s.length && (s[i] == '-' || s[i] == '!' || s[i] == '+')) {
                val op = s[i]; i++
                if (op == '!' && i < s.length && s[i] == '=') fail("Operador inesperado '!='.")
                return MolangNode.Unary(op, unary())
            }
            return primary()
        }

        fun primary(): MolangNode {
            ws()
            if (i >= s.length) fail("Expressão incompleta.")
            val ch = s[i]
            if (ch == '(') {
                i++
                val e = expr()
                ws()
                if (i >= s.length || s[i] != ')') fail("Faltou fechar o parêntese.")
                i++
                return e
            }
            if (ch == '{') fail("Blocos { } não são suportados na prévia.", unsupported = true)
            if (ch == '\'') {
                val end = s.indexOf('\'', i + 1)
                if (end < 0) fail("Texto sem aspas de fechamento.")
                val v = s.substring(i + 1, end)
                i = end + 1
                return MolangNode.Str(v)
            }
            if (ch.isDigit() || (ch == '.' && i + 1 < s.length && s[i + 1].isDigit())) return number()
            if (ch.isLetter() || ch == '_') return identifier()
            fail("Caractere inesperado '$ch'.")
        }

        fun number(): MolangNode {
            val st = i
            while (i < s.length && s[i].isDigit()) i++
            if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) i++ }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || ((s[i + 1] == '-' || s[i + 1] == '+') && i + 2 < s.length && s[i + 2].isDigit()))) {
                i += 2
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'f' || s[i] == 'F')) { val v = s.substring(st, i).toDouble(); i++; return MolangNode.Num(v) }
            return MolangNode.Num(s.substring(st, i).toDouble())
        }

        fun identifier(): MolangNode {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            val raw = s.substring(st, i)
            val name = normalize(raw)
            if (name.endsWith(".")) fail("Nome incompleto '$raw'.")
            ws()
            if (i < s.length && s[i] == '[') fail("Arrays não são suportados na prévia.", unsupported = true)
            if (s.startsWith("->", i)) fail("O operador -> não é suportado na prévia.", unsupported = true)
            if (i < s.length && s[i] == '(') {
                i++
                val args = ArrayList<MolangNode>()
                ws()
                if (i < s.length && s[i] == ')') i++ else {
                    while (true) {
                        args.add(expr())
                        ws()
                        if (i < s.length && s[i] == ',') { i++; continue }
                        if (i < s.length && s[i] == ')') { i++; break }
                        fail("Esperava ',' ou ')' na chamada de '$raw'.")
                    }
                }
                if (name.startsWith("math.")) {
                    val fn = name.removePrefix("math.")
                    val ar = ARITY[fn] ?: fail("A função '$raw' não é suportada na prévia.", unsupported = true)
                    if (args.size != ar) fail("'$raw' espera $ar argumento(s), recebeu ${args.size}.")
                }
                return MolangNode.Call(name, args)
            }
            val known = name == "true" || name == "false" || name == "math.pi" ||
                name.startsWith("query.") || name.startsWith("variable.") || name.startsWith("temp.") || name.startsWith("context.")
            if (name.startsWith("math.") && name != "math.pi") fail("'$raw' não é uma constante conhecida de math.", unsupported = true)
            if (!known) {
                if (name.startsWith("array.") || name.startsWith("geometry.") || name.startsWith("material.") || name.startsWith("texture.")) {
                    fail("'$raw' não é suportado na prévia.", unsupported = true)
                }
                fail("Nome desconhecido '$raw' (use query., variable., temp. ou math.).")
            }
            return MolangNode.Name(name)
        }
    }
}
