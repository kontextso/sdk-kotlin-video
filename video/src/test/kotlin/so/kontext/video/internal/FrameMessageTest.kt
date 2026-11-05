package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import so.kontext.video.internal.frame.FrameMessage

/** Reading a page message, as a table, with no protocol rule: the envelope, the limit, the fields, the playhead. */
class FrameMessageTest {
    private fun parse(raw: String?, limit: Long = 30_000) = FrameMessage.parse(raw, "ad-1", limit)

    private fun message(fields: String) = parse("""{"adId":"ad-1","type":"video.progress"${if (fields.isEmpty()) "" else ", $fields"}}""")!!

    @Test fun aBrokenEnvelopeIsNull() {
        val broken = listOf(
            null to "no text",
            "" to "empty",
            "not json" to "not JSON",
            "[1, 2]" to "an array",
            "\"ad.loaded\"" to "a string",
            """{"type":"ad.loaded"}""" to "no id",
            """{"adId":"ad-2","type":"ad.loaded"}""" to "another ad's id",
            """{"adId":1,"type":"ad.loaded"}""" to "an id that is not a string",
            """{"adId":"ad-1"}""" to "no type",
            """{"adId":"ad-1","type":7}""" to "a type that is not a string",
        )
        for ((raw, why) in broken) assertNull(parse(raw), why)
        assertNotNull(parse("""{"adId":"ad-1","type":"ad.loaded"}"""))
        assertEquals("something.new", parse("""{"adId":"ad-1","type":"something.new"}""")!!.type, "any type: the protocol judges it")
    }

    @Test fun theLimitIsOneMegabyteInUtf8() {
        fun sized(bytes: Int): String {
            val head = """{"adId":"ad-1","type":"ad.loaded","pad":""""
            val tail = "\"}"
            return head + "x".repeat(bytes - head.length - tail.length) + tail
        }
        val limit = FrameMessage.MAX_MESSAGE_BYTES
        assertEquals(1024 * 1024, limit)
        assertNotNull(parse(sized(limit)), "at the limit")
        assertNull(parse(sized(limit + 1)), "just over")
        assertNull(parse("""{"adId":"ad-1","type":"ad.loaded","pad":"${"é".repeat(limit / 2)}"}"""), "two-byte characters count twice")
    }

    @Test fun aMessageNestedTooDeepIsABrokenEnvelopeAndNeverParsed() {
        assertNull(parse("[".repeat(100_000)), "past JsonParser.MAX_DEPTH: no StackOverflowError")
        val deep = "{\"adId\":\"ad-1\",\"type\":\"ad.loaded\",\"pad\":" + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertNull(parse(deep))
    }

    @Test fun thePlayheadIsRoundedAndClamped() {
        val table = listOf(
            "" to 0L,
            "\"playhead\": 1234" to 1_234L,
            "\"playhead\": 1234.4" to 1_234L,
            "\"playhead\": 1234.5" to 1_235L,
            "\"playhead\": -5" to 0L,
            "\"playhead\": 99999999" to 30_000L,
            "\"playhead\": null" to 0L,
        )
        for ((fields, expected) in table) assertEquals(expected, message(fields).playheadMillis, fields)
        assertEquals(86_400_000L, parse("""{"adId":"ad-1","type":"video.pause","playhead":1e12}""", limit = 86_400_000)!!.playheadMillis)
    }

    @Test fun theOffsetIsANumberOnly() {
        val table = listOf(
            "\"offset\": 5000" to 5_000.0,
            "\"offset\": 0.4" to 0.4,
            "\"offset\": -1" to -1.0,
            "\"offset\": \"5000\"" to null,
            "\"offset\": true" to null,
            "\"offset\": {}" to null,
            "" to null,
        )
        for ((fields, expected) in table) assertEquals(expected, message(fields).offset, fields)
    }

    @Test fun theFieldsAreTakenAsSent() {
        val click = parse(
            """{"adId":"ad-1","type":"ad.click","url":"https://a.test","fallbackUrl":"https://b.test","area":"endCard"}""",
        )!!
        assertEquals(listOf("ad.click", "https://a.test", "https://b.test", "endCard"), listOf(click.type, click.url, click.fallbackUrl, click.area))
        val open = parse("""{"adId":"ad-1","type":"ad.open","url":"https://c.test","purpose":"adChoices"}""")!!
        assertEquals("adChoices", open.purpose)
        assertEquals("skip", parse("""{"adId":"ad-1","type":"ad.close","reason":"skip"}""")!!.reason)
        assertEquals(402.0, parse("""{"adId":"ad-1","type":"ad.error","code":402}""")!!.code)
        val bare = parse("""{"adId":"ad-1","type":"ad.loaded"}""")!!
        assertEquals(listOf(null, null, null, null, null, null), listOf(bare.url, bare.fallbackUrl, bare.area, bare.purpose, bare.reason, bare.code))
    }
}
