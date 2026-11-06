package so.kontext.video.internal

import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.measurement.Tracker
import so.kontext.video.internal.network.BeaconClient
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import java.net.CacheRequest
import java.net.CacheResponse
import java.net.ResponseCache
import java.net.URI
import java.net.URLConnection
import java.time.OffsetDateTime
import java.util.UUID

/** The Swift tracker suite's rules: selection, fire ids, macros and their encoding, delivery. */
@RunWith(RobolectricTestRunner::class)
class ParityTrackerTest : ParityHarness() {
    // region Tracker

    private fun entry(event: String, url: String, offset: Long? = null, part: String? = null) = AdPayload.TrackingEvent(event, url, offset, part)

    @Test fun clicksAndPlaybackTogglesFireEveryTimeEachWithItsOwnFireId() {
        // The contract's list, written out: derived from `Tracker.REPEATABLE`, a name dropped there would pass.
        val repeatable = listOf("click", "mute", "pause", "resume", "unmute")
        val tracker = Tracker("ad-1", (repeatable + "start").map { entry(it, "https://t.test/b?e=$it&fid=[KONTEXT_FIREID]") })
        val ids = repeatable.associateWith { listOf(tracker.fire(it), tracker.fire(it)) }
        tracker.fire("start")
        tracker.fire("start")
        for (event in repeatable) {
            val fids = beacons.map(::query).filter { it["e"] == event }.map { it["fid"] }
            assertEquals("$event fires each time, each with its own fire id", ids[event], fids)
            assertEquals(2, fids.toSet().size)
        }
        assertEquals("start is once", 1, beacons.count { query(it)["e"] == "start" })
        val second = ids.getValue("click").last()
        assertEquals(
            "the click-through carries its click's fire id; a bracket that is no macro survives",
            "https://ads.test/redirect?fid=$second&q=%5Bkept%5D",
            tracker.clickThrough("https://ads.test/redirect?fid=%5BKONTEXT_FIREID%5D&q=%5Bkept%5D", second),
        )
    }

    @Test fun everyFireCarriesItsOwnFireIdSharedByItsUrls() {
        fun of(event: String, copy: Int) = entry(event, "https://t.test/b?e=$event&copy=$copy&fid=[KONTEXT_FIREID]")
        val tracker = Tracker("ad-1", listOf(of("impression", 1), of("impression", 2), of("click", 1), of("click", 2), of("click", 3)))
        val impression = tracker.fire("impression")
        val click = tracker.fire("click")
        val fired = beacons.map(::query)
        assertEquals("one occurrence, one id across its URLs", listOf(impression, impression), fired.filter { it["e"] == "impression" }.map { it["fid"] })
        assertEquals(listOf(click, click, click), fired.filter { it["e"] == "click" }.map { it["fid"] })
        assertTrue("a different UUID per occurrence", impression != click && UUID.fromString(impression).toString() == impression)
    }

    @Test fun aBeaconIsAGetThatNoHttpCacheAnswers() {
        // An app may install a process-wide HTTP cache; a beacon must reach the server every time all the same.
        val consulted = mutableListOf<URI>()
        ResponseCache.setDefault(object : ResponseCache() {
            override fun get(uri: URI, method: String, headers: MutableMap<String, MutableList<String>>): CacheResponse? {
                consulted += uri
                return null
            }

            override fun put(uri: URI, connection: URLConnection): CacheRequest? = null
        })
        Environment.sendBeacon = BeaconClient::send
        val tracker = Tracker("ad-1", listOf(entry("loaded", server.url("/events/ad-1/loaded").toString())))
        tracker.fire("loaded")
        await { requests("/events/ad-1/loaded").isNotEmpty() }
        assertEquals("GET", requests("/events/ad-1/loaded").single().method)
        assertTrue("the cache is never asked: $consulted", consulted.isEmpty())
    }

    @Test fun anEntryIsSelectedByItsPart() {
        val tracker = Tracker(
            "ad-1",
            listOf(entry("creativeView", "https://t.test/b?e=cv"), entry("creativeView", "https://t.test/b?e=cv&p=endCard", part = "endCard"), entry("click", "https://t.test/b?e=c&p=endCard", part = "endCard")),
        )
        tracker.fire("creativeView", part = "endCard")
        tracker.fire("click") // the main creative has no click entry here
        tracker.fire("creativeView")
        tracker.fire("click", part = "endCard")
        assertEquals(listOf("cv/endCard", "cv/main", "c/endCard"), beacons.map(::query).map { "${it["e"]}/${it["p"] ?: "main"}" })
    }

    @Test fun progressIsSelectedByItsOffset() {
        val tracker = Tracker("ad-1", listOf(entry("progress", "https://t.test/p?o=5000", offset = 5_000), entry("progress", "https://t.test/p?o=10000", offset = 10_000)))
        tracker.fire("progress", offsetMillis = 4_000)
        tracker.fire("progress", offsetMillis = 5_000)
        tracker.fire("progress", offsetMillis = 5_000)
        assertEquals(listOf("https://t.test/p?o=5000"), beacons)
    }

    @Test fun progressFiresEveryPassedCheckpointOnceEachWithItsOwnFireIdAndOneIdAcrossItsUrls() {
        fun of(offset: Long, copy: Int) = entry("progress", "https://t.test/p?offset=$offset&copy=$copy&fid=[KONTEXT_FIREID]", offset = offset)
        val tracker = Tracker("ad-1", listOf(of(10_000, 1), of(5_000, 1), of(10_000, 2), of(15_000, 1)))
        tracker.fireProgress(4_999)
        assertTrue(beacons.isEmpty())
        tracker.fireProgress(12_000) // the page skipped 5000 and rounded 10 000 up
        tracker.fireProgress(12_000)
        val fired = beacons.map(::query)
        assertEquals("5000 once, 10 000 for both its URLs, 15 000 not yet", listOf("5000", "10000", "10000"), fired.map { it["offset"] })
        val tenK = fired.filter { it["offset"] == "10000" }.map { it["fid"] }
        assertEquals("one checkpoint, one fire id across its URLs", 1, tenK.toSet().size)
        assertEquals("separate checkpoints, separate fire ids", 2, fired.map { it["fid"] }.toSet().size)
    }

    @Test fun anEventWithoutAnEntryFiresNothing() {
        val tracker = Tracker("ad-1", AdPayload.parse(Fixtures.json(Fixtures.pause())).tracking + entry("impression", "https://t.test/i"))
        tracker.fire("rewind")
        tracker.fire("start")
        assertTrue("an image creative has no start entry; rewind is not in the list", beacons.isEmpty())
    }

    @Test fun everyMacroIsSubstitutedAndEncodedAsOneQueryValue() {
        val tracker = Tracker(
            "ad-1",
            listOf(entry("error", "https://t.test/e?code=[ERRORCODE]&cph=[MEDIAPLAYHEAD]&aph=[ADPLAYHEAD]&cb=[CACHEBUSTING]&ts=[TIMESTAMP]")),
        )
        tracker.contentPlayhead = { 3_661_250 }
        tracker.adPlayheadMillis = 7_500
        val before = System.currentTimeMillis()
        tracker.fire("error", errorCode = 405)
        val after = System.currentTimeMillis()
        val raw = beacons.single()
        val fired = query(raw)
        assertEquals("405", fired["code"])
        assertEquals("[MEDIAPLAYHEAD] is HH:MM:SS.mmm", "01:01:01.250", fired["cph"])
        assertEquals("[ADPLAYHEAD] is HH:MM:SS.mmm", "00:00:07.500", fired["aph"])
        assertTrue("[CACHEBUSTING] is 8 digits: $raw", Regex("[?&]cb=\\d{8}(&|$)").containsMatchIn(raw))
        val ts = fired.getValue("ts")
        assertTrue("[TIMESTAMP] is ISO 8601 with milliseconds, in the device's zone: $ts", Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(Z|[+-]\\d{2}:\\d{2})$").matches(ts))
        val parsed = OffsetDateTime.parse(ts).toInstant().toEpochMilli()
        assertTrue("the moment of firing", parsed in before..after)
        assertTrue("colons in playheads and the timestamp are escaped: $raw", raw.contains("cph=01%3A01%3A01.250") && !raw.substringAfter("ts=").contains(":"))
    }

    @Test fun bothContentPlayheadNamesShareOneReadAndAnUnknownMacroIsEncodedOnce() {
        var reads = 0
        val tracker = Tracker("ad-1", listOf(entry("impression", "https://t.test/i?old=[CONTENTPLAYHEAD]&new=[MEDIAPLAYHEAD]&x=[KONTEXT_UNKNOWN]")))
        tracker.contentPlayhead = {
            reads += 1
            reads * 1_000L // advances on every read
        }
        tracker.fire("impression")
        val fired = query(beacons.single())
        assertEquals("both names, one moment", "00:00:01.000", fired["old"])
        assertEquals("00:00:01.000", fired["new"])
        assertEquals("read once per beacon", 1, reads)
        assertEquals("a macro the SDK does not know stays in place", "[KONTEXT_UNKNOWN]", fired["x"])
        assertTrue("its brackets are encoded, nothing re-encoded: ${beacons.single()}", beacons.single().contains("x=%5BKONTEXT_UNKNOWN%5D") && !beacons.single().contains("%25"))
    }

    @Test fun aPartlyEncodedPlayheadMacroIsStillFilled() {
        var reads = 0
        val tracker = Tracker("ad-1", listOf(entry("impression", "https://t.test/i?new=%5BMEDIAPLAYHEAD]&old=[CONTENTPLAYHEAD%5D")))
        tracker.contentPlayhead = {
            reads += 1
            61_500
        }
        tracker.fire("impression")
        val fired = query(beacons.single())
        assertEquals("an encoded opening bracket only", "00:01:01.500", fired["new"])
        assertEquals("an encoded closing bracket only", "00:01:01.500", fired["old"])
        assertEquals("read once for both", 1, reads)
    }

    @Test fun theContentPlayheadIsNotReadWhenNoUrlAsksForIt() {
        var reads = 0
        val tracker = Tracker("ad-1", listOf(entry("impression", "https://t.test/i?cb=[CACHEBUSTING]")))
        tracker.contentPlayhead = {
            reads += 1
            1
        }
        tracker.fire("impression")
        assertEquals(1, beacons.size)
        assertEquals(0, reads)
        assertFalse(tracker.readsContentPlayhead)
    }

    @Test fun aWinnerIsEncodedAsOneQueryValue() {
        val tracker = Tracker("ad-1", listOf(entry("notUsed", "https://t.test/n?winner=[KONTEXT_WINNER]&wp=[KONTEXT_WINNINGPRICE]")))
        tracker.fire("notUsed", notUsed = Tracker.NotUsed.Outbid(2.5, "Ad/Net;work?x=1&y=2 +"))
        val raw = beacons.single()
        assertEquals("decoded, the name is intact", "Ad/Net;work?x=1&y=2 +", query(raw)["winner"])
        assertEquals("2.5", query(raw)["wp"])
        assertTrue("nothing a parser could take for a delimiter: $raw", raw.contains("winner=Ad%2FNet%3Bwork%3Fx%3D1%26y%3D2%20%2B&"))
    }

    @Test fun anInvalidUrlIsSkippedWithoutStoppingTheOthers() {
        Environment.sendBeacon = BeaconClient::send
        val valid = { copy: Int -> entry("impression", server.url("/events/ad-1/impression?copy=$copy").toString()) }
        val invalid = entry("impression", "")
        Tracker("ad-1", listOf(invalid, valid(1), invalid, entry("impression", "not a url"), valid(2), invalid)).fire("impression")
        await { requests("/events/ad-1/impression").size >= 2 }
        settle()
        assertEquals(listOf("1", "2"), requests("/events/ad-1/impression").map { query(it.path)["copy"] }.sortedBy { it })
    }

    @Test fun aFailedBeaconIsNotRetried() {
        answer("/events/") { MockResponse().setResponseCode(503) }
        Environment.sendBeacon = BeaconClient::send
        val tracker = Tracker("ad-1", listOf(entry("impression", server.url("/events/ad-1/impression").toString())))
        tracker.fire("impression")
        await { requests("/events/ad-1/impression").isNotEmpty() }
        settle()
        tracker.fire("impression")
        settle()
        assertEquals("fired once, whatever happened to it", 1, requests("/events/ad-1/impression").size)
    }

    // endregion
}
