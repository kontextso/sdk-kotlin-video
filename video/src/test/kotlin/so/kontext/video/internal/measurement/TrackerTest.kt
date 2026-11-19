package so.kontext.video.internal.measurement

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.network.BeaconClient
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment

class TrackerTest {
    private val sent = mutableListOf<String>()

    @BeforeEach fun capture() {
        Environment.sendBeacon = { url, _ ->
            sent += url
            null
        }
    }

    @AfterEach fun restore() {
        Environment.sendBeacon = BeaconClient::send
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

    @Test fun theClickThroughCarriesTheClicksFireId() {
        assertEquals("https://brand.test/?fid=abc", tracker().clickThrough("https://brand.test/?fid=%5BKONTEXT_FIREID%5D", "abc"))
    }

    /** One entry per beacon the page's events can cause, its URL its own name, the error code and playhead in it. */
    private fun everyBeacon() = Tracker(
        "ad-1",
        listOf(
            "loaded", "error", "iconClick", "start", "firstQuartile", "midpoint", "thirdQuartile", "complete", "skip",
            "pause", "resume", "mute", "unmute", "click", "close",
        ).map { AdPayload.TrackingEvent(it, "https://t.test/$it?e=[ERRORCODE]&a=[ADPLAYHEAD]", null, null) } +
            listOf(
                AdPayload.TrackingEvent("progress", "https://t.test/p1", 1_000, null),
                AdPayload.TrackingEvent("progress", "https://t.test/p3", 3_000, null),
                AdPayload.TrackingEvent("creativeView", "https://t.test/card", null, "endCard"),
                AdPayload.TrackingEvent("creativeView", "https://t.test/view", null, null),
            ),
    )

    /** The beacons [event] fires, by the last path segment of their URLs, with the error code when one was set. */
    private fun Tracker.beacons(event: FrameEvent): List<String> {
        sent.clear()
        record(event)
        return sent.map { url ->
            val name = url.substringAfterLast('/').substringBefore('?')
            val code = url.substringAfter("e=", "").substringBefore('&')
            if (code.isEmpty()) name else "$name($code)"
        }
    }

    @Test fun eachPageEventFiresItsOwnBeaconsAndNoOthers() {
        val video = { event: FrameEvent.VideoEvent -> FrameEvent.Video(event, 0) }
        val table = listOf(
            FrameEvent.Loaded to listOf("loaded"),
            FrameEvent.Error(405) to listOf("error(405)"),
            FrameEvent.Open("https://a.test", "adChoices") to listOf("iconClick"),
            FrameEvent.Open("https://a.test", "privacy") to emptyList(),
            FrameEvent.Click("https://a.test", null, so.kontext.video.VideoAd.Click.Area.Creative) to emptyList(),
            FrameEvent.Close(FrameEvent.CloseReason.CLOSE) to emptyList(),
            video(FrameEvent.VideoEvent.START) to listOf("start"),
            video(FrameEvent.VideoEvent.FIRST_QUARTILE) to listOf("firstQuartile"),
            video(FrameEvent.VideoEvent.MIDPOINT) to listOf("midpoint"),
            video(FrameEvent.VideoEvent.THIRD_QUARTILE) to listOf("thirdQuartile"),
            video(FrameEvent.VideoEvent.PAUSE) to listOf("pause"),
            video(FrameEvent.VideoEvent.RESUME) to listOf("resume"),
            video(FrameEvent.VideoEvent.MUTE) to listOf("mute"),
            video(FrameEvent.VideoEvent.UNMUTE) to listOf("unmute"),
            FrameEvent.Skip(0) to listOf("skip"),
            FrameEvent.Complete(0) to listOf("complete", "card"),
        )
        for ((event, expected) in table) assertEquals(expected, everyBeacon().beacons(event), event.toString())
    }

    @Test fun progressIsCaughtUpOnlyOnceTheVideoStarted() {
        val tracker = everyBeacon()
        assertEquals(listOf("mute"), tracker.beacons(FrameEvent.Video(FrameEvent.VideoEvent.MUTE, 5_000)), "a mute before the start")
        assertEquals(emptyList<String>(), tracker.beacons(FrameEvent.Progress(5_000, 5_000)), "progress before the start")
        assertEquals(listOf("start", "p1"), tracker.beacons(FrameEvent.Video(FrameEvent.VideoEvent.START, 1_000)), "its own beacon, then the checkpoints")
        assertEquals(listOf("p3"), tracker.beacons(FrameEvent.Progress(2_000, 3_000)), "up to the greater of offset and playhead")
        assertEquals(emptyList<String>(), tracker.beacons(FrameEvent.Progress(9_000, 9_000)), "each checkpoint once")
        val skipped = everyBeacon()
        assertEquals(listOf("skip"), skipped.beacons(FrameEvent.Skip(9_000)), "a skip before the start fires no progress")
        val completed = everyBeacon()
        completed.record(FrameEvent.Video(FrameEvent.VideoEvent.START, 0))
        assertEquals(listOf("p1", "p3", "complete", "card"), completed.beacons(FrameEvent.Complete(30_000)), "the checkpoints first, then complete, then the end card")
    }

    @Test fun onlyTheViewersTogglesAndClicksRepeat() {
        val tracker = everyBeacon()
        val twice = listOf("pause", "resume", "mute", "unmute", "click")
        for (event in twice + listOf("start", "loaded", "error", "skip", "complete", "close", "iconClick")) {
            sent.clear()
            tracker.fire(event)
            tracker.fire(event)
            assertEquals(if (event in twice) 2 else 1, sent.size, event)
        }
        assertEquals(twice.toSet(), Tracker.REPEATABLE)
    }

    @Test fun thePlayheadOfEachEventIsTheAdPlayheadOfItsBeacons() {
        val tracker = everyBeacon()
        tracker.record(FrameEvent.Video(FrameEvent.VideoEvent.START, 1_500))
        assertTrue(sent.first().endsWith("a=00%3A00%3A01.500"), sent.first())
        sent.clear()
        tracker.record(FrameEvent.Skip(2_250))
        assertTrue(sent.single().endsWith("a=00%3A00%3A02.250"), sent.single())
        assertEquals(2_250, tracker.adPlayheadMillis)
        sent.clear()
        tracker.record(FrameEvent.Error(400))
        assertTrue(sent.single().endsWith("e=400&a=00%3A00%3A02.250"), "an error keeps the last playhead: ${sent.single()}")
    }

    @Test fun anEntryIsMatchedByItsPartAndItsOffset() {
        val tracker = everyBeacon()
        assertTrue(tracker.has("creativeView", "endCard"))
        assertTrue(tracker.has("creativeView", null))
        assertFalse(tracker.has("creativeView", "other"))
        assertFalse(tracker.has("impression", null))
        tracker.fire("progress", offsetMillis = 2_000)
        assertEquals(emptyList<String>(), sent, "no entry at that offset")
        tracker.fire("progress", offsetMillis = 3_000)
        assertEquals(listOf("https://t.test/p3"), sent)
    }

    @Test fun theContentPlayheadIsReadOncePerOperationAndNestedCallsShareIt() {
        var reads = 0
        var playhead = 1_000L
        val tracker = Tracker("ad-1", listOf(AdPayload.TrackingEvent("pause", "https://t.test/p?c=[CONTENTPLAYHEAD]", null, null))).apply {
            contentPlayhead = {
                reads += 1
                playhead
            }
        }
        tracker.withContentPlayhead {
            tracker.fire("pause")
            playhead = 2_000
            tracker.withContentPlayhead { tracker.fire("pause") }
        }
        assertEquals(1, reads)
        assertEquals(listOf("https://t.test/p?c=00%3A00%3A01.000", "https://t.test/p?c=00%3A00%3A01.000"), sent)
        runCatching { tracker.withContentPlayhead { error("app code threw") } }
        sent.clear()
        tracker.fire("pause")
        assertEquals(listOf("https://t.test/p?c=00%3A00%3A02.000"), sent, "a throw inside leaves no frozen value behind")
    }

    @Test fun aBeaconFiredFromInsideThePlayersReadTakesTheLastValueAndReadsNothing() {
        var reads = 0
        lateinit var tracker: Tracker
        tracker = Tracker("ad-1", listOf(AdPayload.TrackingEvent("pause", "https://t.test/p?c=[CONTENTPLAYHEAD]", null, null))).apply {
            contentPlayhead = {
                reads += 1
                // The app's player runs app code, which may end the ad and fire its beacons on the spot.
                if (reads == 2) tracker.fire("pause")
                reads * 1_000L
            }
        }
        tracker.withContentPlayhead { tracker.fire("pause") }
        tracker.withContentPlayhead { tracker.fire("pause") }
        assertEquals(2, reads)
        assertEquals(
            listOf("https://t.test/p?c=00%3A00%3A01.000", "https://t.test/p?c=00%3A00%3A01.000", "https://t.test/p?c=00%3A00%3A02.000"),
            sent,
            "the nested beacon has the value read before; the operation's own, its new read",
        )
    }

    @Test fun withoutAUrlThatAsksTheContentPlayheadIsNeverRead() {
        var reads = 0
        val tracker = Tracker("ad-1", listOf(AdPayload.TrackingEvent("pause", "https://t.test/p", null, null))).apply {
            contentPlayhead = {
                reads += 1
                0
            }
        }
        assertFalse(tracker.readsContentPlayhead)
        tracker.withContentPlayhead { tracker.fire("pause") }
        assertEquals(0, reads)
        tracker.withContentPlayhead(alsoFor = listOf("https://brand.test/?c=%5BMEDIAPLAYHEAD%5D")) { Unit }
        assertEquals(1, reads, "a click's URL may ask for it")
    }
}
