package so.kontext.video.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import so.kontext.video.internal.payloads.JsonParser
import so.kontext.video.internal.payloads.bool
import so.kontext.video.internal.payloads.double
import so.kontext.video.internal.payloads.has
import so.kontext.video.internal.payloads.long
import so.kontext.video.internal.payloads.putIfNotNull
import so.kontext.video.internal.payloads.str

/** The lenient readers as a table: every kind of JSON value under each reader, and what the request writer leaves out. */
class JsonFieldsTest {
    /** `{"v": <value>}` parsed strictly, as an answer is; `absent` for an object without `v`. */
    private fun field(value: String): JsonObject = JsonParser.parse(if (value == "absent") "{}" else """{"v": $value}""").jsonObject

    @Test fun eachReaderTakesItsOwnTypeOnly() {
        // value → str, bool, double, has
        val table = listOf(
            "\"x\"" to listOf("x", null, null, true),
            "\"\"" to listOf("", null, null, true),
            "\"true\"" to listOf("true", null, null, true),
            "\"1.5\"" to listOf("1.5", null, null, true),
            "true" to listOf(null, true, null, true),
            "false" to listOf(null, false, null, true),
            "1.5" to listOf(null, null, 1.5, true),
            "0" to listOf(null, null, 0.0, true),
            "-0" to listOf(null, null, -0.0, true),
            "1e400" to listOf(null, null, Double.POSITIVE_INFINITY, true),
            "-1e400" to listOf(null, null, Double.NEGATIVE_INFINITY, true),
            "null" to listOf(null, null, null, false),
            "absent" to listOf(null, null, null, false),
            "{}" to listOf(null, null, null, true),
            "[]" to listOf(null, null, null, true),
            "[1]" to listOf(null, null, null, true),
        )
        for ((value, expected) in table) {
            val obj = field(value)
            assertEquals(expected, listOf(obj.str("v"), obj.bool("v"), obj.double("v"), obj.has("v")), value)
        }
    }

    @Test fun aWholeNumberIsALongWithinTheExactRangeOfADouble() {
        val table = listOf(
            "5" to 5L,
            "-5" to -5L,
            "0" to 0L,
            "-0" to 0L,
            "5.0" to 5L,
            "1E2" to 100L,
            "5.5" to null,
            "0.1" to null,
            "9000000000000000.0" to 9_000_000_000_000_000L,
            "-9000000000000000.0" to -9_000_000_000_000_000L,
            "9000000000000002.0" to null,
            // kotlinx reads an exponent without a fraction as a whole number, past the Double range: only `sizes`
            // reads a long today, and it allows 1 to 10 000, so the two forms never decide anything.
            "1e16" to 10_000_000_000_000_000L,
            "1.0e16" to null,
            "1e19" to null,
            "9223372036854775807" to Long.MAX_VALUE,
            "-9223372036854775808" to Long.MIN_VALUE,
            "9223372036854775808" to null,
            "1e400" to null,
            "\"5\"" to null,
            "true" to null,
            "null" to null,
            "absent" to null,
            "[5]" to null,
        )
        for ((value, expected) in table) assertEquals(expected, field(value).long("v"), value)
    }

    @Test fun aRequestLeavesOutWhatIsAbsentAndWritesTheRest() {
        val body = buildJsonObject {
            putIfNotNull("absent", null)
            putIfNotNull("text", "x")
            putIfNotNull("int", 3)
            putIfNotNull("double", 1.5)
            putIfNotNull("bool", false)
            putIfNotNull("element", JsonArray(listOf(JsonPrimitive(1))))
            putIfNotNull("list", listOf("a", 2, null, true))
            putIfNotNull("map", mapOf("k" to "v", 1 to null))
        }
        assertEquals(
            """{"text":"x","int":3,"double":1.5,"bool":false,"element":[1],"list":["a",2,null,true],"map":{"k":"v","1":null}}""",
            body.toString(),
        )
        assertEquals(JsonNull, body["list"]!!.let { (it as JsonArray)[2] })
    }

    @Test fun aValueTheWriterCannotEncodeFailsLoudly() {
        for (value in listOf<Any>(Any(), listOf(listOf(1)), mapOf("k" to Any()))) {
            assertThrows<IllegalStateException>("$value") { buildJsonObject { putIfNotNull("v", value) } }
        }
    }
}
