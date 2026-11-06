package so.kontext.video.internal

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import so.kontext.video.internal.payloads.JsonParser

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

    @Test fun standardJsonIsRead() {
        val text = """{"a": [true, false, null, 0, -0, 1.5, -2e10, 3E+2, 4e-1], "s": "TRUE NaN 01 {\"a\":1,\"a\":2} [", """ +
            """"o": {"a": 1}, "p": {"a": 2}, "list": [{"a": 1}, {"a": 2}], "e": "\"a\""}"""
        val tree = JsonParser.parse(text).jsonObject
        assertEquals("TRUE NaN 01 {\"a\":1,\"a\":2} [", tree["s"]!!.jsonPrimitive.content)
        assertEquals(9, (tree["a"] as kotlinx.serialization.json.JsonArray).size)
    }
}
