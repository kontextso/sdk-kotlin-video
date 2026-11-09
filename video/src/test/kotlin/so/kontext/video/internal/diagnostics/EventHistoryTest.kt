package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The recent events an error report carries: the last 30, as `DebugReporter` recorded them. */
class EventHistoryTest {
    @AfterEach fun clear() = EventHistory.clear()

    private fun event(name: String) = buildJsonObject {
        put("occurredAt", "t")
        put("name", name)
    }

    @Test fun onlyTheLastHistoryLengthAreKept() {
        repeat(EventHistory.HISTORY_LENGTH + 5) { EventHistory.record(event("event.$it")) }
        val recent = EventHistory.recent()
        assertEquals(EventHistory.HISTORY_LENGTH, recent.size)
        assertEquals("event.5", recent.first()["name"]!!.jsonPrimitive.content)
        assertEquals("event.${EventHistory.HISTORY_LENGTH + 4}", recent.last()["name"]!!.jsonPrimitive.content)
    }

    @Test fun recentIsTheEventAsRecorded() {
        val recorded = buildJsonObject {
            put("occurredAt", "t")
            put("name", "ads.response")
            put("sessionId", "s-1")
            put(
                "properties",
                buildJsonObject {
                    put("format", "pause")
                    put("held", 2)
                },
            )
        }
        EventHistory.record(recorded)
        val kept = EventHistory.recent().single()
        assertEquals(recorded, kept)
        assertTrue(kept["properties"]!!.jsonObject.keys == setOf("format", "held"), "every property stays: the server stores what it is sent")
    }
}
