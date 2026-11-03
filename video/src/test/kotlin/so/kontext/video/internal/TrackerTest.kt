package so.kontext.video.internal

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.rendering.Tracker
import so.kontext.video.internal.support.Environment

class TrackerTest {
    private val sent = mutableListOf<String>()

    @BeforeEach fun capture() {
        Environment.sendBeacon = { url, _ -> sent += url }
    }

    @AfterEach fun restore() {
        Environment.sendBeacon = Tracker.Companion::get
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

    @Test fun aWinningPriceIsWrittenAsTheSwiftSdkWritesIt() {
        // What Swift's String(Double) printed for each value.
        val swift = mapOf(
            3.0 to "3.0", 2.4 to "2.4", 0.0001 to "0.0001", 0.00001 to "1e-05", 0.0005 to "0.0005", 1e15 to "1000000000000000.0",
            9_007_199_254_740_992.0 to "9007199254740992.0", 9_007_199_254_740_994.0 to "9.007199254740994e+15", 1e16 to "1e+16",
            1e20 to "1e+20", 1.5e20 to "1.5e+20", 1.5e-7 to "1.5e-07", 123456789.123 to "123456789.123", -2.5 to "-2.5", 0.1 to "0.1",
            Double.NaN to "nan", Double.POSITIVE_INFINITY to "inf", Double.NEGATIVE_INFINITY to "-inf", 1e-300 to "1e-300",
            // Where the digits themselves are the hard part.
            1e23 to "1e+23", 5e-324 to "5e-324", Double.MAX_VALUE to "1.7976931348623157e+308", 0.1 + 0.2 to "0.30000000000000004",
            9.5367431640625e-07 to "9.5367431640625e-07", 2.2250738585072014e-308 to "2.2250738585072014e-308", 1e22 to "1e+22",
            4.35e-05 to "4.35e-05", 1.23456789e15 to "1234567890000000.0", 2e-4 to "0.0002", 1.0e7 to "10000000.0", 123456.0 to "123456.0",
            1.1 to "1.1", 100.0 to "100.0", 0.00012345 to "0.00012345",
            Double.fromBits(0x43a4dbd1460f41bcL) to "7.515124997460372e+17", // where String.format rounded the last digit up
            // 2^-24: below a power of two the gap is half as wide; the nearest 16-digit decimal misses, its neighbour does not.
            Double.fromBits(0x3e70000000000000L) to "5.960464477539063e-08", -Double.fromBits(0x3e70000000000000L) to "-5.960464477539063e-08",
        )
        assertEquals(swift, swift.mapValues { (value, _) -> Tracker.number(value) })
    }
}
