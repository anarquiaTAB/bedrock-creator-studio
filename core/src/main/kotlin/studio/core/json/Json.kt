package studio.core.json

/** Árvore JSON que preserva a ordem das chaves e o texto original dos números. */
sealed class JsonValue

object JsonNull : JsonValue() {
    override fun toString() = "null"
}

data class JsonBool(val value: Boolean) : JsonValue()

/** [raw] mantém o texto exato (ex.: "1.0" não vira "1"). */
data class JsonNum(val raw: String) : JsonValue() {
    fun toIntOrNull(): Int? = raw.toIntOrNull()
    fun toDouble(): Double = raw.toDouble()
}

data class JsonStr(val value: String) : JsonValue()

data class JsonArr(val items: List<JsonValue>) : JsonValue()

class JsonObj(val fields: Map<String, JsonValue>) : JsonValue() {
    operator fun get(key: String): JsonValue? = fields[key]
    fun obj(key: String): JsonObj? = fields[key] as? JsonObj
    fun arr(key: String): JsonArr? = fields[key] as? JsonArr
    fun str(key: String): String? = (fields[key] as? JsonStr)?.value
    fun int(key: String): Int? = (fields[key] as? JsonNum)?.toIntOrNull()

    /** Devolve uma cópia com [key] definido; as demais chaves (inclusive desconhecidas) são mantidas. */
    fun with(key: String, value: JsonValue): JsonObj {
        val copy = LinkedHashMap<String, JsonValue>(fields)
        copy[key] = value
        return JsonObj(copy)
    }

    fun without(key: String): JsonObj {
        val copy = LinkedHashMap<String, JsonValue>(fields)
        copy.remove(key)
        return JsonObj(copy)
    }

    override fun equals(other: Any?) = other is JsonObj && fields == other.fields
    override fun hashCode() = fields.hashCode()
    override fun toString() = Json.write(this, pretty = false)

    companion object {
        fun empty(): JsonObj = JsonObj(LinkedHashMap<String, JsonValue>())
    }
}

fun jsonObj(vararg pairs: Pair<String, JsonValue>): JsonObj {
    val map = LinkedHashMap<String, JsonValue>()
    for ((k, v) in pairs) map[k] = v
    return JsonObj(map)
}

fun jsonInts(values: List<Int>): JsonArr = JsonArr(values.map { JsonNum(it.toString()) })

class JsonParseException(val detail: String, val line: Int, val column: Int) :
    Exception("$detail (linha $line, coluna $column)")

object Json {
    private const val MAX_DEPTH = 256

    /**
     * Lê JSON. Por padrão aceita comentários (// e /* */) e BOM, como o Bedrock.
     * Comentários não são mantidos ao reescrever com [write].
     */
    fun parse(text: String, allowComments: Boolean = true): JsonValue =
        Parser(text.removePrefix("\uFEFF"), allowComments).parseDocument()

    fun parseObject(text: String, allowComments: Boolean = true): JsonObj =
        parse(text, allowComments) as? JsonObj
            ?: throw JsonParseException("A raiz do arquivo deve ser um objeto JSON", 1, 1)

    fun write(value: JsonValue, pretty: Boolean = true): String {
        val sb = StringBuilder()
        writeValue(value, sb, pretty, 0)
        return sb.toString()
    }

    private fun writeValue(v: JsonValue, sb: StringBuilder, pretty: Boolean, level: Int) {
        when (v) {
            is JsonNull -> sb.append("null")
            is JsonBool -> sb.append(if (v.value) "true" else "false")
            is JsonNum -> sb.append(v.raw)
            is JsonStr -> writeString(v.value, sb)
            is JsonArr -> {
                if (v.items.isEmpty()) {
                    sb.append("[]")
                } else if (!pretty || v.items.all { it !is JsonObj && it !is JsonArr }) {
                    sb.append('[')
                    v.items.forEachIndexed { i, item ->
                        if (i > 0) sb.append(if (pretty) ", " else ",")
                        writeValue(item, sb, pretty, level)
                    }
                    sb.append(']')
                } else {
                    sb.append("[\n")
                    v.items.forEachIndexed { i, item ->
                        sb.append("  ".repeat(level + 1))
                        writeValue(item, sb, pretty, level + 1)
                        if (i < v.items.size - 1) sb.append(',')
                        sb.append('\n')
                    }
                    sb.append("  ".repeat(level)).append(']')
                }
            }
            is JsonObj -> {
                if (v.fields.isEmpty()) {
                    sb.append("{}")
                } else if (!pretty) {
                    sb.append('{')
                    var first = true
                    for ((k, item) in v.fields) {
                        if (!first) sb.append(',')
                        first = false
                        writeString(k, sb)
                        sb.append(':')
                        writeValue(item, sb, false, level)
                    }
                    sb.append('}')
                } else {
                    sb.append("{\n")
                    var i = 0
                    for ((k, item) in v.fields) {
                        sb.append("  ".repeat(level + 1))
                        writeString(k, sb)
                        sb.append(": ")
                        writeValue(item, sb, true, level + 1)
                        if (i < v.fields.size - 1) sb.append(',')
                        sb.append('\n')
                        i++
                    }
                    sb.append("  ".repeat(level)).append('}')
                }
            }
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String, private val allowComments: Boolean) {
        private var i = 0

        fun fail(msg: String, at: Int = i): Nothing {
            var line = 1
            var col = 1
            for (k in 0 until minOf(at, s.length)) {
                if (s[k] == '\n') {
                    line++
                    col = 1
                } else {
                    col++
                }
            }
            throw JsonParseException(msg, line, col)
        }

        fun parseDocument(): JsonValue {
            skipWs()
            if (i >= s.length) fail("Arquivo vazio")
            val v = parseValue(0)
            skipWs()
            if (i < s.length) fail("Conteúdo inesperado após o valor JSON")
            return v
        }

        private fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++
                } else if (allowComments && c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                    while (i < s.length && s[i] != '\n') i++
                } else if (allowComments && c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                    val end = s.indexOf("*/", i + 2)
                    if (end < 0) fail("Comentário não terminado")
                    i = end + 2
                } else {
                    break
                }
            }
        }

        private fun parseValue(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("Aninhamento profundo demais")
            if (i >= s.length) fail("Fim inesperado do arquivo")
            val c = s[i]
            return when {
                c == '{' -> parseObject(depth)
                c == '[' -> parseArray(depth)
                c == '"' -> JsonStr(parseString())
                c == 't' -> literal("true", JsonBool(true))
                c == 'f' -> literal("false", JsonBool(false))
                c == 'n' -> literal("null", JsonNull)
                c == '-' || c in '0'..'9' -> parseNumber()
                else -> fail("Caractere inesperado '$c'")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) fail("Valor inválido")
            i += word.length
            return value
        }

        private fun parseObject(depth: Int): JsonValue {
            i++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return JsonObj(map)
            }
            while (true) {
                skipWs()
                if (i >= s.length) fail("Objeto não terminado")
                if (s[i] != '"') fail("Esperada uma chave entre aspas")
                val keyPos = i
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') fail("Esperado ':' após a chave")
                i++
                skipWs()
                val value = parseValue(depth + 1)
                if (map.containsKey(key)) fail("Chave duplicada \"$key\"", keyPos)
                map[key] = value
                skipWs()
                if (i >= s.length) fail("Objeto não terminado")
                if (s[i] == ',') {
                    i++
                } else if (s[i] == '}') {
                    i++
                    break
                } else {
                    fail("Esperado ',' ou '}'")
                }
            }
            return JsonObj(map)
        }

        private fun parseArray(depth: Int): JsonValue {
            i++ // [
            val items = ArrayList<JsonValue>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return JsonArr(items)
            }
            while (true) {
                skipWs()
                items.add(parseValue(depth + 1))
                skipWs()
                if (i >= s.length) fail("Lista não terminada")
                if (s[i] == ',') {
                    i++
                } else if (s[i] == ']') {
                    i++
                    break
                } else {
                    fail("Esperado ',' ou ']'")
                }
            }
            return JsonArr(items)
        }

        private fun parseString(): String {
            val start = i
            i++ // aspas de abertura
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("String não terminada", start)
                val c = s[i]
                if (c == '"') {
                    i++
                    return sb.toString()
                }
                if (c == '\\') {
                    i++
                    if (i >= s.length) fail("String não terminada", start)
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) fail("Sequência \\u incompleta")
                            val hex = s.substring(i + 1, i + 5)
                            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                fail("Sequência \\u inválida")
                            }
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> fail("Sequência de escape inválida")
                    }
                    i++
                } else if (c < ' ') {
                    fail("Caractere de controle não permitido em string")
                } else {
                    sb.append(c)
                    i++
                }
            }
        }

        private fun digits(): Int {
            val st = i
            while (i < s.length && s[i] in '0'..'9') i++
            return i - st
        }

        private fun parseNumber(): JsonValue {
            val start = i
            if (s[i] == '-') i++
            if (i >= s.length) fail("Número inválido")
            if (s[i] == '0') {
                i++
                if (i < s.length && s[i] in '0'..'9') fail("Zeros à esquerda não são permitidos")
            } else if (s[i] in '1'..'9') {
                digits()
            } else {
                fail("Número inválido")
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (digits() == 0) fail("Esperados dígitos após '.'")
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (digits() == 0) fail("Esperados dígitos no expoente")
            }
            return JsonNum(s.substring(start, i))
        }
    }
}
