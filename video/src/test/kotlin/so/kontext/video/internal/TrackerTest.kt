package so.kontext.video.internal

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import so.kontext.video.internal.rendering.Tracker
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.wire.AdPayload

class TrackerTest {
    private val sent = mutableListOf<String>()

    @BeforeEach fun capture() {
        Environment.sendBeacon = { url, _ -> sent += url }
    }

    @AfterEach fun restore() {
        Environment.sendBeacon = Tracker::get
    }

    private fun tracker() = Tracker("ad-1", AdPayload.parse(Fixtures.json(Fixtures.overlay())).tracking)

    @Test fun anEventFiresOnceAndAClickEveryTime() {
        val tracker = tracker()
        tracker.fire("impression")
        tracker.fire("impression")
        tracker.fire("click")
        tracker.fire("click")
        assertEquals(1, sent.count { it.startsWith("https://t.test/i") })
        assertEquals(2, sent.count { it == "https://t.test/c" })
        assertFalse(sent.contains("https://t.test/end"), "the end card's click is its own part")
    }

    @Test fun theMacrosAreFilledAndTheContentPlayheadReadOnce() {
        var reads = 0
        val tracker = tracker().apply {
            contentPlayhead = {
                reads += 1
                3_723_456
            }
        }
        val fireId = tracker.fire("impression")
        val url = sent.single()
        assertTrue(url.contains("ph=01%3A02%3A03.456"), url)
        assertTrue(url.contains("fid=$fireId"), url)
        assertTrue(Regex("cb=\\d{8}").containsMatchIn(url), url)
        assertEquals(1, reads)
    }

    @Test fun progressFiresEveryPassedCheckpointInOrder() {
        val tracker = tracker()
        tracker.fireProgress(6_000)
        assertEquals(listOf("https://t.test/p2", "https://t.test/p5"), sent)
        tracker.fireProgress(9_000)
        assertEquals(2, sent.size, "each checkpoint once")
    }

    @Test fun anUnknownMacroStaysEncodedAndTheNotUsedReasonIsFilled() {
        val tracker = Tracker(
            "ad-1",
            listOf(AdPayload.TrackingEvent("notUsed", "https://t.test/n?r=[KONTEXT_REASON]&w=[KONTEXT_WINNER]&p=[KONTEXT_WINNINGPRICE]&x=[OTHER]", null, null)),
        )
        tracker.fire("notUsed", notUsed = Tracker.NotUsed.Outbid(2.4, "Other Network"))
        assertEquals("https://t.test/n?r=outbid&w=Other%20Network&p=2.4&x=%5BOTHER%5D", sent.single())
    }

    @Test fun playheadsAreFormattedAndClamped() {
        assertEquals("00:00:00.000", Tracker.playhead(-5))
        assertEquals("00:01:05.250", Tracker.playhead(65_250))
        assertEquals("87600:00:00.000", Tracker.playhead(Long.MAX_VALUE))
    }

    @Test fun theClickThroughCarriesTheClicksFireId() {
        assertEquals("https://brand.test/?fid=abc", tracker().clickThrough("https://brand.test/?fid=%5BKONTEXT_FIREID%5D", "abc"))
    }
}
