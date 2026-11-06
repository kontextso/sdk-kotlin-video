package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How the reports write their fields: strings cut at a code point, state values as JSON that stays JSON, the time in UTC. */
class ReportFormatTest {
    @Test fun reportFieldsAreCutAtACodePoint() {
        assertEquals("a".repeat(64), ReportFormat.capped("a".repeat(80)))
        val emoji = "😀".repeat(20) // 4 bytes each
        assertEquals("😀".repeat(16), ReportFormat.capped(emoji))
    }

    @Test fun aFieldIsKeptWholeUpToItsLimitInBytes() {
        val table = listOf(
            "" to "",
            "a".repeat(64) to "a".repeat(64),
            "a".repeat(65) to "a".repeat(64),
            "é".repeat(32) to "é".repeat(32), // 2 bytes each: 64 exactly
            "é".repeat(33) to "é".repeat(32),
            "a" + "é".repeat(32) to "a" + "é".repeat(31), // the last é would end at byte 65
            "a".repeat(63) + "😀" to "a".repeat(63), // a 4-byte code point never cut in half
        )
        for ((text, expected) in table) {
            val capped = ReportFormat.capped(text)
            assertEquals(expected, capped, "${text.length} chars")
            assertTrue(capped.toByteArray().size <= ReportFormat.MAX_FIELD_LENGTH)
        }
    }

    @Test fun aStateValueIsJsonEvenWhenItHasNoJsonNumber() {
        val table = listOf<Pair<Any, JsonPrimitive>>(
            1.5 to JsonPrimitive(1.5),
            Double.NaN to JsonPrimitive("NaN"),
            Double.POSITIVE_INFINITY to JsonPrimitive("Infinity"),
            Double.NEGATIVE_INFINITY to JsonPrimitive("-Infinity"),
            1.5f to JsonPrimitive(1.5f),
            Float.NaN to JsonPrimitive("NaN"),
            Float.POSITIVE_INFINITY to JsonPrimitive("Infinity"),
            42 to JsonPrimitive(42),
            Long.MAX_VALUE to JsonPrimitive(Long.MAX_VALUE),
            true to JsonPrimitive(true),
            "text" to JsonPrimitive("text"),
            listOf(1, 2) to JsonPrimitive("[1, 2]"),
        )
        for ((value, expected) in table) assertEquals(expected, ReportFormat.json(value), "$value")
    }

    @Test fun theTimestampIsUtcToTheMillisecond() {
        assertTrue(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z").matches(ReportFormat.timestamp()))
    }
}
