package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import so.kontext.video.internal.frame.FrameBridge.Origin
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.frame.FrameProtocol
import so.kontext.video.internal.frame.FrameProtocol.NavigationDecision
import so.kontext.video.internal.frame.FrameProtocol.State
import so.kontext.video.internal.frame.FrameProtocol.Step
import java.net.URI

/** The frame protocol as tables, with no WebView: message × state → step, the envelope, the order, the bounds. */
class FrameProtocolTableTest {
    private val url = "https://frames.test/v2/frame/ad-1"
    private val page = Origin("https", "frames.test", 443)
    private var now = 1_000_000L

    /** Origins without Android's Uri: enough for the URLs of these tests. */
    private fun originOf(target: String): Origin? {
        val uri = runCatching { URI(target) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
        return Origin(scheme, host, port)
    }

    private fun protocol(isVideo: Boolean = true, durationMillis: Long? = null) =
        FrameProtocol("ad-1", originOf(url), isVideo, durationMillis, ::originOf) { it.startsWith("https://") }

    private fun FrameProtocol.send(type: String, fields: String = "", from: Origin? = page, mainFrame: Boolean = true): Step =
        accept("""{"adId":"ad-1","type":"$type"${if (fields.isEmpty()) "" else ", $fields"}}""", from, mainFrame, now)

    private fun describe(step: Step): String = when (step) {
        Step.Ignore -> "ignore"
        Step.Loaded -> "loaded"
        is Step.Drop -> "drop" + (step.violation?.let { "!$it" } ?: "")
        is Step.Deliver -> step.event::class.simpleName!!
        is Step.EndThenDeliver -> "end+" + step.event::class.simpleName!!
    }

    private fun protocolIn(column: String) = protocol().apply {
        if (column != "loading") send("ad.loaded")
        if (column == "shown" || column == "started") show()
        if (column == "started") send("video.start")
        if (column == "ended") end()
    }

    @Test fun everyMessageInEveryState() {
        val columns = listOf("loading", "loaded", "shown", "started", "ended")
        val link = "\"url\": \"https://brand.test\""
        val table = listOf(
            listOf("ad.loaded", "", "loaded", "ignore", "ignore", "ignore", "ignore"),
            listOf("ad.click", link, "ignore", "ignore", "Click", "Click", "ignore"),
            listOf("ad.open", link, "ignore", "ignore", "Open", "Open", "ignore"),
            listOf("ad.close", "", "ignore", "ignore", "end+Close", "end+Close", "ignore"),
            listOf("video.skip", "", "ignore", "ignore", "end+Skip", "end+Skip", "ignore"),
            listOf("video.start", "", "ignore", "ignore", "Video", "Video", "ignore"),
            listOf("video.firstQuartile", "", "ignore", "ignore", "drop!video.beforeStart", "Video", "ignore"),
            listOf("video.pause", "", "ignore", "ignore", "drop!video.beforeStart", "Video", "ignore"),
            listOf("video.mute", "", "ignore", "ignore", "Video", "Video", "ignore"),
            listOf("video.unmute", "", "ignore", "ignore", "Video", "Video", "ignore"),
            listOf("video.progress", "\"offset\": 1000", "ignore", "ignore", "drop!video.beforeStart", "Progress", "ignore"),
            listOf("video.complete", "", "ignore", "ignore", "drop!video.beforeStart", "Complete", "ignore"),
            listOf("ad.error", "", "end+Error", "end+Error", "end+Error", "end+Error", "ignore"),
            listOf("ad.unknown", "", "drop", "drop", "drop", "drop", "ignore"),
        )
        for (row in table) {
            val (type, fields) = row
            val actual = columns.map { column ->
                val protocol = protocolIn(column)
                protocol.noteTouch(now)
                describe(protocol.send(type, fields))
            }
            assertEquals(row.drop(2), actual, type)
        }
    }

    @Test fun theStatesMoveOnlyForward() {
        val protocol = protocol()
        assertEquals(false, protocol.show(), "not before ad.loaded")
        protocol.send("ad.loaded")
        assertEquals(State.LOADED, protocol.state)
        assertEquals(true, protocol.show())
        assertEquals(false, protocol.show(), "once")
        protocol.send("ad.close")
        assertEquals(State.ENDED, protocol.state)
        assertEquals(false, protocol.end(), "the teardown runs once")
    }

    @Test fun theEnvelopeAndTheSender() {
        val cases = listOf(
            "from another frame" to protocol().accept("""{"adId":"ad-1","type":"ad.loaded"}""", page, false, now),
            "from no origin" to protocol().accept("""{"adId":"ad-1","type":"ad.loaded"}""", null, true, now),
            "from another origin" to protocol().accept("""{"adId":"ad-1","type":"ad.loaded"}""", Origin("https", "evil.test", 443), true, now),
            "another port" to protocol().accept("""{"adId":"ad-1","type":"ad.loaded"}""", Origin("https", "frames.test", 8443), true, now),
        )
        for ((what, step) in cases) assertEquals("drop", describe(step), what)
        for (raw in listOf(null, "not json", "[1]", """{"adId":"ad-2","type":"ad.loaded"}""", """{"adId":"ad-1"}""", """{"type":"ad.loaded"}""")) {
            assertEquals("drop!envelope", describe(protocol().accept(raw, page, true, now)), "$raw")
        }
        assertEquals("drop!envelope", describe(protocol().acceptNonText(page, true)))
        assertEquals("ignore", describe(protocol().acceptNonText(Origin("https", "evil.test", 443), true)))
        assertEquals("loaded", describe(protocol().send("ad.loaded")))
    }

    @Test fun aViolationIsReportedOnce() {
        val protocol = protocol()
        assertEquals("drop!envelope", describe(protocol.accept("not json", page, true, now)))
        assertEquals("drop", describe(protocol.accept("not json", page, true, now)))
        protocol.send("ad.loaded")
        protocol.show()
        assertEquals("drop", describe(protocol.send("video.complete")), "another kind, still once")
    }

    @Test fun thePlayheadIsClampedToTheVideoAndADay() {
        fun playhead(sent: String, durationMillis: Long? = null): Long {
            val protocol = protocol(durationMillis = durationMillis).apply {
                send("ad.loaded")
                show()
            }
            return ((protocol.send("video.start", "\"playhead\": $sent") as Step.Deliver).event as FrameEvent.Video).playheadMillis
        }
        assertEquals(0, playhead("-5"))
        assertEquals(1_500, playhead("1500"))
        assertEquals(86_400_000, playhead("1e300"))
        assertEquals(7_000, playhead("60000", durationMillis = 6_000), "the duration and a second of grace")
        assertEquals(0, playhead("\"x\""), "not a number")
    }

    @Test fun aProgressOffsetIsCheckedAndCapped() {
        fun progress(offset: String, durationMillis: Long? = null): String {
            val protocol = protocol(durationMillis = durationMillis).apply {
                send("ad.loaded")
                show()
                send("video.start")
            }
            val step = protocol.send("video.progress", "\"offset\": $offset")
            return if (step is Step.Deliver) "${(step.event as FrameEvent.Progress).offsetMillis}" else describe(step)
        }
        assertEquals("3000", progress("3000"))
        assertEquals("7000", progress("60000", durationMillis = 6_000))
        assertEquals("drop!progress.offset", progress("-1"))
        assertEquals("drop!progress.offset", progress("\"3000\""))
        assertEquals("drop!progress.offset", progress("86400001"))
    }

    @Test fun aFractionalPlayheadOrOffsetIsRoundedToTheNearestMillisecond() {
        val protocol = protocol().apply {
            send("ad.loaded")
            show()
        }
        val start = (protocol.send("video.start", "\"playhead\": 1500.5") as Step.Deliver).event as FrameEvent.Video
        assertEquals(1_501, start.playheadMillis)
        val progress = (protocol.send("video.progress", "\"offset\": 2999.6, \"playhead\": 1500.4") as Step.Deliver).event as FrameEvent.Progress
        assertEquals(3_000, progress.offsetMillis)
        assertEquals(1_500, progress.playheadMillis)
        val small = (protocol.send("video.progress", "\"offset\": 0.4") as Step.Deliver).event as FrameEvent.Progress
        assertEquals(0, small.offsetMillis)
        val top = (protocol.send("video.progress", "\"offset\": 86399999.7") as Step.Deliver).event as FrameEvent.Progress
        assertEquals(86_400_000, top.offsetMillis, "within a day before rounding")
    }

    @Test fun aMessageOverOneMegabyteIsABrokenEnvelope() {
        fun message(bytes: Int): String {
            val head = """{"adId":"ad-1","type":"ad.loaded","pad":""""
            val tail = "\"}"
            return head + "x".repeat(bytes - head.length - tail.length) + tail
        }
        assertEquals(FrameProtocol.MAX_MESSAGE_BYTES, message(FrameProtocol.MAX_MESSAGE_BYTES).toByteArray().size)
        assertEquals("loaded", describe(protocol().accept(message(FrameProtocol.MAX_MESSAGE_BYTES), page, true, now)), "at the limit")
        assertEquals("loaded", describe(protocol().accept(message(FrameProtocol.MAX_MESSAGE_BYTES - 1), page, true, now)), "just under")
        assertEquals("drop!envelope", describe(protocol().accept(message(FrameProtocol.MAX_MESSAGE_BYTES + 1), page, true, now)), "just over")
        // Counted in UTF-8 bytes, not characters: two-byte characters reach the limit at half the length.
        val wide = """{"adId":"ad-1","type":"ad.loaded","pad":"${"é".repeat(FrameProtocol.MAX_MESSAGE_BYTES / 2)}"}"""
        assertEquals("drop!envelope", describe(protocol().accept(wide, page, true, now)))
    }

    @Test fun videoStartOpensTheVideo() {
        val protocol = protocol().apply {
            send("ad.loaded")
            show()
        }
        assertEquals("drop!video.beforeStart", describe(protocol.send("video.midpoint")))
        assertEquals("Video", describe(protocol.send("video.start")))
        assertEquals("Video", describe(protocol.send("video.midpoint")))
    }

    @Test fun anErrorCarriesItsCode() {
        fun code(state: String, isVideo: Boolean, fields: String = ""): Int {
            val protocol = protocol(isVideo).apply {
                if (state != "loading") send("ad.loaded")
                if (state == "shown") show()
            }
            return ((protocol.send("ad.error", fields) as Step.EndThenDeliver).event as FrameEvent.Error).code
        }
        assertEquals(400, code("loading", isVideo = true))
        assertEquals(500, code("loaded", isVideo = false))
        assertEquals(405, code("shown", isVideo = true))
        assertEquals(402, code("shown", isVideo = true, "\"code\": 402"))
        assertEquals(900, code("shown", isVideo = true, "\"code\": 42"))
    }

    @Test fun oneTouchOpensOneLinkWithinItsWindow() {
        val protocol = protocol().apply {
            send("ad.loaded")
            show()
        }
        val link = "\"url\": \"https://brand.test\""
        assertEquals("drop", describe(protocol.send("ad.click", link)), "no touch")
        protocol.noteTouch(now)
        now += FrameProtocol.TOUCH_WINDOW_MILLIS
        assertEquals("Click", describe(protocol.send("ad.click", link)), "at the window's edge")
        assertEquals("drop", describe(protocol.send("ad.click", link)), "the touch is spent")
        protocol.noteTouch(now)
        now += FrameProtocol.TOUCH_WINDOW_MILLIS + 1
        assertEquals("drop", describe(protocol.send("ad.open", link)), "too late")
        assertEquals("ignore", describe(protocol.send("ad.click", "\"url\": \"javascript:x\"")), "a link that may not open")
        protocol.noteTouch(now)
        assertEquals("Click", describe(protocol.externalNavigation("https://brand.test", now)))
        assertEquals("drop", describe(protocol.externalNavigation("https://brand.test", now)))
    }

    @Test fun navigation() {
        val protocol = protocol()
        // target, loading, ready
        val table = listOf(
            Triple("https://frames.test/redirect", NavigationDecision.ALLOW, NavigationDecision.CANCEL),
            Triple("about:blank", NavigationDecision.ALLOW, NavigationDecision.CANCEL),
            Triple("https://brand.test", NavigationDecision.EXTERNAL, NavigationDecision.EXTERNAL),
            Triple("$url#card", NavigationDecision.ALLOW, NavigationDecision.ALLOW),
        )
        for ((target, loading, _) in table) assertEquals(loading, protocol.navigationDecision(target, true, url), "loading: $target")
        protocol.send("ad.loaded")
        for ((target, _, ready) in table) assertEquals(ready, protocol.navigationDecision(target, true, url), "ready: $target")
        assertEquals(NavigationDecision.ALLOW, protocol.navigationDecision("https://cdn.test/x", false, url), "a subframe")
        assertNull(originOf("not a url"))
    }
}
