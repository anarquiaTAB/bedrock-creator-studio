package studio.core.addon

import studio.core.diagnostics.Category
import studio.core.diagnostics.Diagnostic
import studio.core.diagnostics.Severity

/**
 * Verificação básica de JavaScript: delimitadores balanceados, strings terminadas e módulos @minecraft/…
 * usados sem dependência no manifest. NÃO é um parser: por isso os problemas de sintaxe saem como AVISO
 * (heurística) e não bloqueiam a exportação. Também NÃO confere se as funções chamadas existem na Script API.
 */
object ScriptLint {
    private val IMPORT = Regex("""(?:from|import)\s*\(?\s*["'](@minecraft/[A-Za-z0-9_\-]+)["']""")

    private fun suspect(msg: String, file: String?, line: Int, col: Int) =
        Diagnostic(Severity.WARNING, Category.SYNTAX, "SCRIPT_SYNTAX_SUSPECT", "$msg (verificação básica, não é um parser completo)", file, line, col)

    fun check(source: String, file: String? = null, declaredModules: Set<String>? = null): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        out += syntax(source, file)
        if (declaredModules != null) {
            for (m in IMPORT.findAll(source).map { it.groupValues[1] }.toSet()) {
                if (m !in declaredModules) {
                    out.add(Diagnostic(Severity.WARNING, Category.STRUCTURE, "SCRIPT_MODULE_NOT_DECLARED", "O script importa '$m', que não está nas dependências do manifest.", file))
                }
            }
        }
        return out
    }

    private class Open(val ch: Char, val line: Int, val col: Int)

    private fun syntax(s: String, file: String?): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        val stack = ArrayList<Open>()
        val templateBraces = ArrayList<Int>() // profundidade da pilha em que um ${ foi aberto
        var i = 0
        var line = 1
        var col = 1
        var prev: Char? = null
        var lastWord = ""

        fun advance() {
            if (s[i] == '\n') { line++; col = 1 } else col++
            i++
        }

        fun readTemplate() {
            // posicionado após a crase de abertura
            while (i < s.length) {
                val c = s[i]
                if (c == '\\') { advance(); if (i < s.length) advance(); continue }
                if (c == '`') { advance(); return }
                if (c == '$' && i + 1 < s.length && s[i + 1] == '{') {
                    stack.add(Open('{', line, col))
                    templateBraces.add(stack.size)
                    advance(); advance()
                    return
                }
                advance()
            }
            out.add(suspect("Template literal não terminado", file, line, col))
        }

        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '/' -> while (i < s.length && s[i] != '\n') advance()
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val sl = line; val sc = col
                    advance(); advance()
                    var closed = false
                    while (i < s.length) {
                        if (s[i] == '*' && i + 1 < s.length && s[i + 1] == '/') { advance(); advance(); closed = true; break }
                        advance()
                    }
                    if (!closed) out.add(suspect("Comentário de bloco não terminado", file, sl, sc))
                }
                c == '"' || c == '\'' -> {
                    val sl = line; val sc = col
                    advance()
                    var closed = false
                    while (i < s.length) {
                        val d = s[i]
                        if (d == '\\') { advance(); if (i < s.length) advance(); continue }
                        if (d == '\n') break
                        if (d == c) { advance(); closed = true; break }
                        advance()
                    }
                    if (!closed) out.add(suspect("String não terminada", file, sl, sc))
                    prev = c
                }
                c == '`' -> { advance(); readTemplate(); prev = '`' }
                c == '/' && (prev == null || prev in "(,=:[!&|?{};+-*%<>~^" || lastWord == "return" || lastWord == "typeof") -> {
                    // possível literal de regex: tenta ler até a próxima '/' não escapada na mesma linha
                    var j = i + 1
                    var inClass = false
                    var found = -1
                    while (j < s.length && s[j] != '\n') {
                        val d = s[j]
                        if (d == '\\') { j += 2; continue }
                        if (d == '[') inClass = true else if (d == ']') inClass = false
                        else if (d == '/' && !inClass) { found = j; break }
                        j++
                    }
                    if (found > 0) { while (i <= found) advance() } else advance()
                    prev = '/'
                }
                c == '(' || c == '[' || c == '{' -> { stack.add(Open(c, line, col)); prev = c; advance() }
                c == ')' || c == ']' || c == '}' -> {
                    val want = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (stack.isEmpty() || stack.last().ch != want) {
                        out.add(suspect("'$c' sem abertura correspondente", file, line, col))
                    } else {
                        stack.removeAt(stack.size - 1)
                        if (c == '}' && templateBraces.isNotEmpty() && templateBraces.last() == stack.size + 1) {
                            templateBraces.removeAt(templateBraces.size - 1)
                            advance()
                            readTemplate()
                            prev = '`'
                            continue
                        }
                    }
                    prev = c
                    advance()
                }
                c.isLetterOrDigit() || c == '_' || c == '$' -> {
                    val st = i
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) advance()
                    lastWord = s.substring(st, i)
                    prev = 'a'
                }
                c.isWhitespace() -> advance()
                else -> { prev = c; lastWord = ""; advance() }
            }
        }
        for (o in stack) out.add(suspect("'${o.ch}' aberto e nunca fechado", file, o.line, o.col))
        return out
    }
}

object FunctionLint {
    /** Arquivos .mcfunction: um comando por linha, sem '/' inicial; '#' inicia comentário. */
    fun check(text: String, file: String? = null): List<Diagnostic> {
        val out = mutableListOf<Diagnostic>()
        text.lines().forEachIndexed { i, raw ->
            val t = raw.trim()
            if (t.startsWith("/")) {
                out.add(Diagnostic(Severity.WARNING, Category.STRUCTURE, "MCFUNCTION_SLASH", "Comandos em .mcfunction não levam '/' inicial.", file, i + 1, 1))
            }
        }
        return out
    }
}

object Scaffold {
    /** Esqueleto mínimo; não usa eventos específicos porque os nomes variam entre versões da Script API. */
    fun mainJs(): String = "import { world } from \"@minecraft/server\";\n\n" +
        "// Escreva seu código aqui. Consulte a documentação oficial da Script API\n" +
        "// para a versão do módulo declarada no manifest.json deste pacote.\n"

    fun mcfunction(): String = "# Um comando por linha, sem '/' inicial.\n"
}
