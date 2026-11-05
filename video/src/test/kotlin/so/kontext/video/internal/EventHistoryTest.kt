package so.kontext.video.internal

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import so.kontext.video.internal.diagnostics.EventHistory
import so.kontext.video.internal.diagnostics.ReportFormat

/** The recent events an error report carries: the last 30, reduced to the listed keys, strings capped. */
class EventHistoryTest {
    @AfterEach fun clear() = EventHistory.clear()

    @Test fun onlyTheLastHistoryLengthAreKept() {
        repeat(EventHistory.HISTORY_LENGTH + 5) { EventHistory.record("t$it", "event.$it", null, null) }
        val recent = EventHistory.recent()
        assertEquals(EventHistory.HISTORY_LENGTH, recent.size)
        assertEquals("event.5", recent.first().name)
        assertEquals("event.${EventHistory.HISTORY_LENGTH + 4}", recent.last().name)
    }

    @Test fun recentKeepsListedKeysOnlyAndCapsStrings() {
        val long = "é".repeat(100)
        EventHistory.record("t", long, long, mapOf("format" to long, "playhead" to 1200, "email" to "a@b.test"))
        val moment = EventHistory.recent().single()
        assertEquals(ReportFormat.capped(long), moment.name)
        assertEquals(ReportFormat.capped(long), moment.adId)
        assertEquals(mapOf("format" to ReportFormat.capped(long), "playhead" to 1200), moment.state)
    }

    @Test fun aStateWithNoListedKeyIsLeftOut() {
        EventHistory.record("t", "ad.shown", "ad-1", mapOf("secret" to "x"))
        assertNull(EventHistory.recent().single().state)
    }
}
