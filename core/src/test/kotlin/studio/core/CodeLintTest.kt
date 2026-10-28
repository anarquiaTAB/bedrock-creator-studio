package studio.core

import studio.core.addon.*
import studio.core.diagnostics.Severity
import kotlin.test.*

class CodeLintTest {
    @Test fun cleanScriptHasNoFindings() {
        val src = """
            import { world } from "@minecraft/server";
            // comentário com ( [ {
            /* bloco com " ' ` */
            const r = /[")]/g;
            const s = "texto com ) e ]";
            const t = `a ${'$'}{x + `b ${'$'}{y}`} c`;
            function f(a) { return [a, { b: (a / 2) }]; }
        """.trimIndent()
        val d = ScriptLint.check(src, "main.js", setOf("@minecraft/server"))
        assertTrue(d.isEmpty(), d.joinToString())
    }

    @Test fun detectsUnbalancedAndUnterminated() {
        val open = ScriptLint.check("function f() {", "a.js").single()
        assertEquals(1, open.line)
        assertEquals(14, open.column)
        assertEquals(Severity.WARNING, open.severity)
        assertTrue(ScriptLint.check("let a = 1);", "a.js").isNotEmpty())
        val str = ScriptLint.check("let a = 1;\nlet b = \"abc;\nlet c = 2;", "a.js")
        assertEquals(2, str.first().line)
        assertTrue(ScriptLint.check("let t = `abc", "a.js").isNotEmpty())
        assertTrue(ScriptLint.check("/* aberto", "a.js").isNotEmpty())
        assertTrue(ScriptLint.check("function f() {", "a.js").none { it.severity == Severity.ERROR })
    }

    @Test fun checksDeclaredModules() {
        val src = "import { world } from \"@minecraft/server\";\nimport { x } from \"@minecraft/server-ui\";"
        val d = ScriptLint.check(src, "a.js", setOf("@minecraft/server"))
        assertEquals(listOf("SCRIPT_MODULE_NOT_DECLARED"), d.map { it.code })
        assertTrue(d.single().message.contains("server-ui"))
        assertTrue(ScriptLint.check(src, "a.js", null).isEmpty())
        assertTrue(ScriptLint.check(Scaffold.mainJs(), "main.js", setOf("@minecraft/server")).isEmpty())
    }

    @Test fun mcfunctionLint() {
        val d = FunctionLint.check("/say oi\nsay ok\n# comentário", "f.mcfunction")
        assertEquals(1, d.size)
        assertEquals(1, d.single().line)
        assertTrue(FunctionLint.check(Scaffold.mcfunction()).isEmpty())
    }
}
