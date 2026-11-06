package so.kontext.video.internal.payloads

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Strict JSON, as iOS's decoder reads it: what kotlinx's lenient parser would take is refused. */
class JsonParserTest {
    @Test fun nonStandardTokensAreRefused() {
        val refused = listOf(
            """{"enabled": TRUE}""", """{"enabled": True}""", """{"a": NaN}""", """{"a": Infinity}""", """{"a": -Infinity}""",
            """{"a": 01}""", """{"a": abc}""", """{"a": tru}""", """{"a": +1}""", """{"a": 1.}""", """{"a": .5}""", """[nul]""",
        )
        for (text in refused) assertThrows<IllegalArgumentException>(text) { JsonParser.parse(text) }
    }

    @Test fun aKeyTwiceInOneObjectIsRefused() {
        for (text in listOf("""{"enabled": false, "enabled": true}""", """{"a": "x", "a": "y"}""", """[{"b": {"a": 1, "a": 2}}]""")) {
            assertThrows<IllegalArgumentException>(text) { JsonParser.parse(text) }
        }
    }

    @Test fun aRawControlCharacterInAStringIsRefusedAndItsEscapeIsRead() {
        val raw = listOf('\n', '\t', '\r', '\u0000', '\u001F')
        for (char in raw) {
            assertThrows<IllegalArgumentException>("value ${char.code}") { JsonParser.parse("{\"disabledReason\": \"a${char}b\"}") }
            assertThrows<IllegalArgumentException>("key ${char.code}") { JsonParser.parse("{\"a${char}b\": 1}") }
        }
        val escaped = JsonParser.parse("""{"r": "a\nb\t\u0000\u001f", "k\n": 1}""").jsonObject
        assertEquals("a\nb\t\u0000\u001f", escaped["r"]!!.jsonPrimitive.content)
        assertEquals(1, escaped["k\n"]!!.jsonPrimitive.content.toInt())
        assertEquals("\u007f é", JsonParser.parse("{\"d\": \"\u007f é\"}").jsonObject["d"]!!.jsonPrimitive.content, "DEL and beyond are text")
    }

    @Test fun standardJsonIsRead() {
        val text = """{"a": [true, false, null, 0, -0, 1.5, -2e10, 3E+2, 4e-1], "s": "TRUE NaN 01 {\"a\":1,\"a\":2} [", """ +
            """"o": {"a": 1}, "p": {"a": 2}, "list": [{"a": 1}, {"a": 2}], "e": "\"a\""}"""
        val tree = JsonParser.parse(text).jsonObject
        assertEquals("TRUE NaN 01 {\"a\":1,\"a\":2} [", tree["s"]!!.jsonPrimitive.content)
        assertEquals(9, (tree["a"] as kotlinx.serialization.json.JsonArray).size)
    }

    @Test fun aKeyTwiceIsFoundThroughItsEscapes() {
        val twice = listOf(
            """{"a": 1, "\u0061": 2}""", """{"\t": 1, "\u0009": 2}""", """{"\n": 1, "\u000a": 2}""", """{"\r": 1, "\u000D": 2}""",
            """{"\b": 1, "\u0008": 2}""", """{"\f": 1, "\u000c": 2}""", """{"/": 1, "\/": 2}""", """{"\"": 1, "\u0022": 2}""",
            """{"\\": 1, "\u005c": 2}""", """{"é": 1, "\u00e9": 2}""", """{"ab": 1, "a\u0062": 2}""",
        )
        for (text in twice) assertThrows<IllegalArgumentException>(text) { JsonParser.parse(text) }
        val distinct = JsonParser.parse("""{"\t": 1, "\n": 2, "\b": 3, "\f": 4, "\r": 5, "\/": 6, "\\": 7, "\"": 8, "\u0061b": 9, "a": 10}""").jsonObject
        assertEquals(10, distinct.size)
    }

    @Test fun aBrokenEscapeInAKeyIsRefused() {
        for (text in listOf("""{"\u00zz": 1}""", """{"\u12": 1}""", """{"\u": 1}""", """{"\x": 1}""")) {
            assertThrows<IllegalArgumentException>(text) { JsonParser.parse(text) }
        }
    }

    @Test fun theDepthLimitIsFiveHundredTwelveArraysOrObjects() {
        val limit = JsonParser.MAX_DEPTH
        JsonParser.parse("[".repeat(limit) + "]".repeat(limit))
        assertThrows<IllegalArgumentException> { JsonParser.parse("[".repeat(limit + 1) + "]".repeat(limit + 1)) }
        val objects = { depth: Int -> "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1) }
        JsonParser.parse(objects(limit))
        assertThrows<IllegalArgumentException> { JsonParser.parse(objects(limit + 1)) }
        // Siblings are not depth: a thousand arrays side by side nest one deep.
        JsonParser.parse("[" + List(1_000) { "[]" }.joinToString(",") + "]")
        JsonParser.parse("""["${"[".repeat(limit + 1)}"]""")
    }

    @Test fun textThatIsNoJsonValueIsRefused() {
        for (text in listOf("", " ", "\n", "1,2", "{} {}", "{\"a\":1", "[1,]", "{\"a\" 1}", "\"open")) {
            assertThrows<IllegalArgumentException>("'$text'") { JsonParser.parse(text) }
        }
        assertEquals("1", JsonParser.parse(" 1 ").toString(), "a bare number is JSON")
    }
}
