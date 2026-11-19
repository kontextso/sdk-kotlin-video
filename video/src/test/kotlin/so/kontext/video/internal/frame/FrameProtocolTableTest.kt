package so.kontext.video.internal.frame

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
        FrameProtocol("ad-1", originOf(url), isVideo, durationMillis, isAllowed = { it.startsWith("https://") })

    private fun FrameProtocol.send(type: String, fields: String = "", from: Origin? = page, mainFrame: Boolean = true): Step =
        accept("""{"bidId":"ad-1","type":"$type"${if (fields.isEmpty()) "" else ", $fields"}}""", from, mainFrame, now)

    private fun describe(step: Step): String = when (step) {
        Step.Ignore -> "ignore"
        Step.Loaded -> "loaded"
        is Step.Drop -> "drop" + (step.violation?.let { "!$it" } ?: "")
        is Step.Deliver -> step.event::class.simpleName!!
        is Step.EndThenDeliver -> "end+" + step.event::class.simpleName!!
    }

    private fun protocolIn(column: String) = protocol().apply {
        if (column != "loading") send("ad.loaded")
        if (column == "shown" || column == "started" || column == "completed") show()
        if (column == "started" || column == "completed") send("video.start")
        if (column == "completed") send("video.complete")
        if (column == "ended") end()
    }

    @Test fun everyMessageInEveryState() {
        val columns = listOf("loading", "loaded", "shown", "started", "completed", "ended")
        val link = "\"url\": \"https://brand.test\""
        val table = listOf(
            listOf("ad.loaded", "", "loaded", "ignore", "ignore", "ignore", "ignore", "ignore"),
            listOf("ad.click", link, "ignore", "ignore", "Click", "Click", "Click", "ignore"),
            listOf("ad.open", link, "ignore", "ignore", "Open", "Open", "Open", "ignore"),
            listOf("ad.close", "", "ignore", "ignore", "end+Close", "end+Close", "end+Close", "ignore"),
            listOf("video.skip", "", "ignore", "ignore", "end+Skip", "end+Skip", "end+Skip", "ignore"),
            listOf("video.start", "", "ignore", "ignore", "Video", "Video", "ignore", "ignore"),
            listOf("video.firstQuartile", "", "ignore", "ignore", "drop!video.beforeStart", "Video", "ignore", "ignore"),
            listOf("video.pause", "", "ignore", "ignore", "drop!video.beforeStart", "Video", "ignore", "ignore"),
            listOf("video.mute", "", "ignore", "ignore", "Video", "Video", "Video", "ignore"),
            listOf("video.unmute", "", "ignore", "ignore", "Video", "Video", "Video", "ignore"),
            listOf("video.progress", "\"offset\": 1000", "ignore", "ignore", "drop!video.beforeStart", "Progress", "ignore", "ignore"),
            listOf("video.complete", "", "ignore", "ignore", "drop!video.beforeStart", "Complete", "ignore", "ignore"),
            listOf("ad.error", "", "end+Error", "end+Error", "end+Error", "end+Error", "end+Error", "ignore"),
            listOf("ad.unknown", "", "drop", "drop", "drop", "drop", "drop", "ignore"),
        )
        for (row in table) {
            val (type, fields) = row
            val actual = columns.map { column ->
                val protocol = protocolIn(column)
                protocol.touchGate.noteTouch(now)
                describe(protocol.send(type, fields))
            }
            assertEquals(row.drop(2), actual, type)
        }
    }

    @Test fun aVideoLongerThanADayHasItsProgressPastADay() {
        val day = 86_400_000L
        val protocol = protocol(durationMillis = 2 * day).apply {
            send("ad.loaded")
            show()
            send("video.start")
        }
        val step = protocol.send("video.progress", "\"offset\": ${day + day / 2}, \"playhead\": ${day + day / 2}")
        val progress = (step as Step.Deliver).event as FrameEvent.Progress
        assertEquals(day + day / 2, progress.offsetMillis)
        // Without a duration, a day is still the bound.
        val unknown = protocol().apply {
            send("ad.loaded")
            show()
            send("video.start")
        }
        assertEquals("drop!progress.offset", describe(unknown.send("video.progress", "\"offset\": ${day + 1}")))
    }

    @Test fun afterTheVideoCompletedOnlyMuteAndUnmuteCount() {
        val protocol = protocolIn("completed")
        for (type in listOf("video.resume", "video.start", "video.pause", "video.firstQuartile", "video.complete")) {
            assertEquals("ignore", describe(protocol.send(type)), type)
        }
        assertEquals("ignore", describe(protocol.send("video.progress", "\"offset\": 1000")))
        assertEquals("Video", describe(protocol.send("video.mute")))
        assertEquals("Video", describe(protocol.send("video.unmute")))
    }

    @Test fun theStatesMoveOnlyForward() {
        val protocol = protocol()
        assertEquals(false, protocol.show(), "not before ad.loaded")
        protocol.send("ad.loaded")
        assertEquals(FramePhase.LOADED, protocol.state)
        assertEquals(true, protocol.show())
        assertEquals(false, protocol.show(), "once")
        protocol.send("ad.close")
        assertEquals(FramePhase.ENDED, protocol.state)
        assertEquals(false, protocol.end(), "the teardown runs once")
    }

    @Test fun theEnvelopeAndTheSender() {
        val cases = listOf(
            "from another frame" to protocol().accept("""{"bidId":"ad-1","type":"ad.loaded"}""", page, false, now),
            "from no origin" to protocol().accept("""{"bidId":"ad-1","type":"ad.loaded"}""", null, true, now),
            "from another origin" to protocol().accept("""{"bidId":"ad-1","type":"ad.loaded"}""", Origin("https", "evil.test", 443), true, now),
            "another port" to protocol().accept("""{"bidId":"ad-1","type":"ad.loaded"}""", Origin("https", "frames.test", 8443), true, now),
        )
        for ((what, step) in cases) assertEquals("drop", describe(step), what)
        for (raw in listOf(null, "not json", "[1]", """{"bidId":"ad-2","type":"ad.loaded"}""", """{"bidId":"ad-1"}""", """{"type":"ad.loaded"}""")) {
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
            val head = """{"bidId":"ad-1","type":"ad.loaded","pad":""""
            val tail = "\"}"
            return head + "x".repeat(bytes - head.length - tail.length) + tail
        }
        assertEquals(FrameMessage.MAX_MESSAGE_BYTES, message(FrameMessage.MAX_MESSAGE_BYTES).toByteArray().size)
        assertEquals("loaded", describe(protocol().accept(message(FrameMessage.MAX_MESSAGE_BYTES), page, true, now)), "at the limit")
        assertEquals("loaded", describe(protocol().accept(message(FrameMessage.MAX_MESSAGE_BYTES - 1), page, true, now)), "just under")
        assertEquals("drop!envelope", describe(protocol().accept(message(FrameMessage.MAX_MESSAGE_BYTES + 1), page, true, now)), "just over")
        // Counted in UTF-8 bytes, not characters: two-byte characters reach the limit at half the length.
        val wide = """{"bidId":"ad-1","type":"ad.loaded","pad":"${"é".repeat(FrameMessage.MAX_MESSAGE_BYTES / 2)}"}"""
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
        protocol.touchGate.noteTouch(now)
        now += TouchGate.TOUCH_WINDOW_MILLIS
        assertEquals("Click", describe(protocol.send("ad.click", link)), "at the window's edge")
        assertEquals("drop", describe(protocol.send("ad.click", link)), "the touch is spent")
        protocol.touchGate.noteTouch(now)
        now += TouchGate.TOUCH_WINDOW_MILLIS + 1
        assertEquals("drop", describe(protocol.send("ad.open", link)), "too late")
        assertEquals("ignore", describe(protocol.send("ad.click", "\"url\": \"javascript:x\"")), "a link that may not open")
    }

    /** Shown, its touch fresh: what a click or an open needs. */
    private fun touched() = protocol().apply {
        send("ad.loaded")
        show()
        touchGate.noteTouch(now)
    }

    @Test fun anErrorCodeIsTheVastRangeAtItsEdgesAnd900Outside() {
        fun code(sent: String): Int = ((touched().send("ad.error", "\"code\": $sent") as Step.EndThenDeliver).event as FrameEvent.Error).code
        val table = listOf("100" to 100, "999" to 999, "99" to 900, "1000" to 900, "0" to 900, "-400" to 900, "1e300" to 900, "1e400" to 900)
        for ((sent, expected) in table) assertEquals(expected, code(sent), sent)
        assertEquals(405, ((touched().send("ad.error", "\"code\": \"402\"") as Step.EndThenDeliver).event as FrameEvent.Error).code, "a string code is none: the failure's")
    }

    @Test fun aClickTakesItsFallbackWhenItsLinkMayNotOpen() {
        fun click(fields: String): String = when (val step = touched().send("ad.click", fields)) {
            is Step.Deliver -> (step.event as FrameEvent.Click).let { "${it.url} | ${it.fallbackUrl}" }
            else -> describe(step)
        }
        val table = listOf(
            "\"url\": \"https://a.test\", \"fallbackUrl\": \"https://b.test\"" to "https://a.test | https://b.test",
            "\"url\": \"javascript:x\", \"fallbackUrl\": \"https://b.test\"" to "https://b.test | null",
            "\"fallbackUrl\": \"https://b.test\"" to "https://b.test | null",
            "\"url\": \"https://a.test\", \"fallbackUrl\": \"javascript:x\"" to "https://a.test | null",
            "\"url\": \"javascript:x\", \"fallbackUrl\": \"javascript:y\"" to "ignore",
            "" to "ignore",
            "\"url\": 7" to "ignore",
        )
        for ((fields, expected) in table) assertEquals(expected, click(fields), fields)
    }

    @Test fun anOpenHasNoFallbackAndItsPurposeIsUnknownWhenLeftOut() {
        val noLink = touched().send("ad.open", "\"url\": \"javascript:x\", \"fallbackUrl\": \"https://b.test\"")
        assertEquals("ignore", describe(noLink), "an open never takes a fallback")
        val open = (touched().send("ad.open", "\"url\": \"https://a.test\"") as Step.Deliver).event as FrameEvent.Open
        assertEquals("unknown", open.purpose)
        val choices = (touched().send("ad.open", "\"url\": \"https://a.test\", \"purpose\": \"adChoices\"") as Step.Deliver).event as FrameEvent.Open
        assertEquals("adChoices", choices.purpose)
    }

    @Test fun aClickLandsOnItsArea() {
        fun area(fields: String) = ((touched().send("ad.click", "\"url\": \"https://a.test\"$fields") as Step.Deliver).event as FrameEvent.Click).area
        assertEquals(so.kontext.video.VideoAd.Click.Area.Creative, area(""))
        assertEquals(so.kontext.video.VideoAd.Click.Area.Creative, area(", \"area\": \"creative\""))
        assertEquals(so.kontext.video.VideoAd.Click.Area.EndCard, area(", \"area\": \"endCard\""))
        assertEquals(so.kontext.video.VideoAd.Click.Area.Other("logo"), area(", \"area\": \"logo\""))
        assertEquals(so.kontext.video.VideoAd.Click.Area.Creative, area(", \"area\": 3"), "an area that is not text is the creative")
    }

    @Test fun aCloseReasonTheSdkDoesNotKnowIsAClose() {
        fun reason(fields: String) = ((touched().send("ad.close", fields) as Step.EndThenDeliver).event as FrameEvent.Close).reason
        val table = listOf(
            "" to FrameEvent.CloseReason.CLOSE,
            "\"reason\": \"close\"" to FrameEvent.CloseReason.CLOSE,
            "\"reason\": \"resume\"" to FrameEvent.CloseReason.RESUME,
            "\"reason\": \"complete\"" to FrameEvent.CloseReason.COMPLETE,
            "\"reason\": \"skip\"" to FrameEvent.CloseReason.CLOSE,
            "\"reason\": \"Resume\"" to FrameEvent.CloseReason.CLOSE,
            "\"reason\": 1" to FrameEvent.CloseReason.CLOSE,
        )
        for ((fields, expected) in table) assertEquals(expected, reason(fields), fields)
    }

    @Test fun aProgressOffsetAtItsBoundsAndPastADouble() {
        fun progress(offset: String): String {
            val protocol = touched().apply { send("video.start") }
            val step = protocol.send("video.progress", "\"offset\": $offset")
            return if (step is Step.Deliver) "${(step.event as FrameEvent.Progress).offsetMillis}" else describe(step)
        }
        assertEquals("0", progress("0"))
        assertEquals("0", progress("-0"))
        assertEquals("86400000", progress("86400000"), "a day exactly")
        assertEquals("drop!progress.offset", progress("1e400"), "infinite")
        assertEquals("drop!progress.offset", progress("null"))
        assertEquals("drop!progress.offset", progress("[1]"))
    }

    @Test fun aNegativeDurationLeavesTheGraceAsTheBound() {
        val protocol = protocol(durationMillis = -5_000).apply {
            send("ad.loaded")
            show()
        }
        val start = (protocol.send("video.start", "\"playhead\": 5000") as Step.Deliver).event as FrameEvent.Video
        assertEquals(1_000, start.playheadMillis)
    }

    @Test fun anEndedPageIgnoresEvenAMessageAlreadyRead() {
        val protocol = protocolIn("ended")
        val message = FrameMessage.parse("""{"bidId":"ad-1","type":"ad.error"}""", "ad-1", 1_000)!!
        assertEquals("ignore", describe(protocol.accept(message, now)))
        assertEquals("ignore", describe(protocol.acceptNonText(page, true)))
        assertEquals(FramePhase.ENDED, protocol.state)
        assertEquals(false, protocol.show())
    }

    @Test fun theFailureCodeFollowsTheFramesPhase() {
        val video = protocol(isVideo = true)
        assertEquals(400, video.failureCode)
        video.send("ad.loaded")
        assertEquals(400, video.failureCode)
        video.show()
        assertEquals(405, video.failureCode)
        val display = protocol(isVideo = false)
        assertEquals(500, display.failureCode)
        display.send("ad.loaded")
        display.show()
        assertEquals(405, display.failureCode)
    }
}
