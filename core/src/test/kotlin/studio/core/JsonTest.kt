package studio.core

import studio.core.json.*
import kotlin.test.*

class JsonTest {
    @Test fun parsesCommentsAndBom() {
        val v = Json.parseObject("\uFEFF{ // c\n \"a\": 1, /* x */ \"b\": [1,2] }")
        assertEquals("1", (v["a"] as JsonNum).raw)
        assertEquals(2, v.arr("b")!!.items.size)
    }

    @Test fun reportsLineAndColumn() {
        val e = assertFailsWith<JsonParseException> { Json.parse("{\n  \"a\": 1,\n  \"b\": }") }
        assertEquals(3, e.line)
        assertEquals(8, e.column)
    }

    @Test fun rejectsDuplicateKeysTrailingCommaAndGarbage() {
        assertFailsWith<JsonParseException> { Json.parse("{\"a\":1,\"a\":2}") }
        assertFailsWith<JsonParseException> { Json.parse("{\"a\":1,}") }
        assertFailsWith<JsonParseException> { Json.parse("[1,2,]") }
        assertFailsWith<JsonParseException> { Json.parse("{} x") }
        assertFailsWith<JsonParseException> { Json.parse("") }
        assertFailsWith<JsonParseException> { Json.parse("{\"a\":\"abc") }
        assertFailsWith<JsonParseException> { Json.parse("[01]") }
    }

    @Test fun preservesNumberText() {
        val v = Json.parse("""{"x": 1.0, "y": 1e3}""")
        assertEquals("""{"x":1.0,"y":1e3}""", Json.write(v, pretty = false))
    }

    @Test fun roundTripKeepsUnknownFields() {
        val src = """{"known":1,"unknown":{"deep":[1,{"z":null}],"s":"a\"b\u00e9"}}"""
        val edited = Json.parseObject(src).with("known", JsonNum("2"))
        val again = Json.parseObject(Json.write(edited))
        assertEquals(Json.parseObject(src).obj("unknown"), again.obj("unknown"))
        assertEquals("2", (again["known"] as JsonNum).raw)
        assertEquals(listOf("known", "unknown"), again.fields.keys.toList())
    }
}
