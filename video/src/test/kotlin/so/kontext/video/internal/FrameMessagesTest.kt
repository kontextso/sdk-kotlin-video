package so.kontext.video.internal

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.webkit.WebMessageCompat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.VideoAd
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.frame.FrameNavigation
import so.kontext.video.internal.frame.FramePhase
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.frame.TouchGate
import so.kontext.video.internal.platform.Environment
import java.time.Duration

/** The frame protocol from the page's side: what each message does in each state, and what is dropped. */
@RunWith(RobolectricTestRunner::class)
class FrameMessagesTest {
    private val url = "https://frames.test/v2/frame/ad-1"
    private val origin = Origin.of(url)
    private val events = mutableListOf<FrameEvent>()
    private var now = 1_000_000L
    private lateinit var bridge: FrameBridge

    @Before fun make() {
        Environment.context = ApplicationProvider.getApplicationContext()
        Environment.now = { now }
        ErrorReporter.resetForTesting()
        bridge = bridge(isVideo = false)
    }

    @After fun reset() {
        FrameBridge.loadTimeoutMillis = 10_000
        ErrorReporter.resetForTesting()
        Environment.reset()
    }

    private fun bridge(isVideo: Boolean) = FrameBridge(Environment.context!!, "ad-1", url, isVideo).also { it.onEvent = { event -> events += event } }

    private fun send(type: String, fields: String = "", to: FrameBridge = bridge) =
        to.receive("""{"adId":"ad-1","type":"$type"${if (fields.isEmpty()) "" else ", $fields"}}""", origin, true)

    private fun shown(to: FrameBridge = bridge) {
        send("ad.loaded", to = to)
        to.show()
        events.clear()
    }

    /** Shown, its video started: what the video's progress, quartiles and complete need first. */
    private fun started(to: FrameBridge = bridge) {
        shown(to)
        send("video.start", to = to)
        events.clear()
    }

    @Test fun aLoadedPageIsPausedWhileItWaitsAndResumedAtShow() {
        val video = bridge(isVideo = true)
        val webView = shadowOf(video.webView)
        assertFalse("loading runs: a video creative buffers its video", webView.wasOnPauseCalled())
        send("ad.loaded", to = video)
        assertEquals(listOf<FrameEvent>(FrameEvent.Loaded), events)
        assertTrue("held off screen: paused", webView.wasOnPauseCalled())
        assertFalse(webView.wasOnResumeCalled())
        video.show()
        assertTrue(webView.wasOnResumeCalled())
    }

    @Test fun beforeTheAdIsShownOnlyLoadedAndErrorCount() {
        send("ad.loaded")
        events.clear()
        bridge.noteTouch()
        send("ad.click", "\"url\": \"https://brand.test\"")
        send("ad.open", "\"url\": \"https://brand.test\"")
        send("ad.close")
        send("video.skip")
        send("video.complete")
        send("video.progress", "\"offset\": 1000")
        send("video.start")
        send("ad.loaded")
        assertTrue("nothing before ad.show: ${events.map { it::class.simpleName }}", events.isEmpty())
        assertEquals(FramePhase.LOADED, bridge.state)
        send("ad.error")
        assertEquals("a display page that fails to load: 500", 500, (events.single() as FrameEvent.Error).code)
    }

    @Test fun aVideoPageFailsWithItsOwnCodesBeforeAndAfterShow() {
        val video = bridge(isVideo = true)
        send("ad.error", to = video)
        assertEquals(400, (events.single() as FrameEvent.Error).code)
        events.clear()
        val shownVideo = bridge(isVideo = true)
        shown(shownVideo)
        send("ad.error", to = shownVideo)
        assertEquals("a failure on screen: 405", 405, (events.single() as FrameEvent.Error).code)
        events.clear()
        val coded = bridge(isVideo = true)
        send("ad.error", "\"code\": 402", to = coded)
        assertEquals("the page's own code wins", 402, (events.single() as FrameEvent.Error).code)
    }

    @Test fun aPageCodeOutsideTheVastRangeGoesAs900() {
        val cases = listOf("0" to 900, "-1" to 900, "42" to 900, "99" to 900, "100" to 100, "999" to 999, "1000" to 900, "${Int.MAX_VALUE}" to 900, "1e300" to 900)
        for ((sent, expected) in cases) {
            events.clear()
            val video = bridge(isVideo = true)
            shown(video)
            send("ad.error", "\"code\": $sent", to = video)
            assertEquals("code $sent", expected, (events.single() as FrameEvent.Error).code)
        }
    }

    @Test fun messagesFromAnywhereButThePageItselfAreDropped() {
        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", origin, isMainFrame = false)
        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", null, true)
        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", Origin.of("https://evil.test"), true)
        bridge.receive(null, origin, true)
        bridge.receive("not json", origin, true)
        bridge.receive("[1, 2]", origin, true)
        bridge.receive("""{"adId":"ad-2","type":"ad.loaded"}""", origin, true)
        bridge.receive("""{"adId":"ad-1"}""", origin, true)
        send("ad.unknown")
        send("video.rewind")
        assertTrue(events.isEmpty())
        assertEquals(FramePhase.LOADING, bridge.state)
    }

    @Test fun aClickNeedsAnAllowedUrlAndOneTouchEach() {
        shown()
        send("ad.click", "\"url\": \"https://brand.test\"")
        assertTrue("no touch before it", events.isEmpty())
        bridge.noteTouch()
        send("ad.click", "\"url\": \"javascript:alert(1)\"")
        send("ad.click")
        assertTrue("a refused or missing url spends no touch", events.isEmpty())
        send("ad.click", "\"url\": \"https://brand.test\", \"fallbackUrl\": \"intent://x\", \"area\": \"endCard\"")
        val click = events.single() as FrameEvent.Click
        assertNull("a refused fallback is left out", click.fallbackUrl)
        assertEquals(VideoAd.Click.Area.EndCard, click.area)
        send("ad.click", "\"url\": \"https://brand.test\"")
        assertEquals("the touch was spent", 1, events.size)
        bridge.noteTouch()
        now += TouchGate.TOUCH_WINDOW_MILLIS + 1
        send("ad.open", "\"url\": \"https://brand.test/why\"")
        assertEquals("a touch too long ago", 1, events.size)
        bridge.noteTouch()
        send("ad.open", "\"url\": \"https://brand.test/why\"")
        assertEquals("unknown", (events.last() as FrameEvent.Open).purpose)
    }

    @Test fun progressTakesMillisecondsWithinADayAFractionRounded() {
        started()
        for (bad in listOf("\"1000\"", "-1", "86400001", "null", "{}")) send("video.progress", "\"offset\": $bad")
        send("video.progress")
        assertTrue(events.isEmpty())
        send("video.progress", "\"offset\": 1.5")
        assertEquals("a fraction rounded to the nearest, as on iOS", 2, (events.single() as FrameEvent.Progress).offsetMillis)
        events.clear()
        send("video.progress", "\"offset\": 2000, \"playhead\": -5")
        val progress = events.single() as FrameEvent.Progress
        assertEquals(2_000, progress.offsetMillis)
        assertEquals("a negative playhead reads as 0", 0, progress.playheadMillis)
        send("video.complete", "\"playhead\": 1e12")
        assertEquals("clamped to a day", 86_400_000, (events.last() as FrameEvent.Complete).playheadMillis)
    }

    @Test fun onScreenTheVideoMomentsArriveAndCloseOrSkipEndsThePage() {
        shown()
        send("video.start", "\"playhead\": 10")
        assertEquals(FrameEvent.VideoEvent.START, (events.single() as FrameEvent.Video).event)
        send("video.skip", "\"playhead\": 5000")
        assertEquals(5_000, (events.last() as FrameEvent.Skip).playheadMillis)
        assertEquals(FramePhase.ENDED, bridge.state)
        val count = events.size
        send("ad.close")
        send("ad.error")
        bridge.webView.webViewClient.onPageStarted(bridge.webView, "https://frames.test/again", null)
        bridge.webView.webViewClient.onRenderProcessGone(bridge.webView, io.mockk.mockk(relaxed = true))
        assertEquals("an ended page is silent", count, events.size)
    }

    @Test fun closeCarriesItsReason() {
        shown()
        send("ad.close", "\"reason\": \"resume\"")
        assertEquals(FrameEvent.CloseReason.RESUME, (events.single() as FrameEvent.Close).reason)
        assertEquals(FrameEvent.CloseReason.COMPLETE, FrameEvent.CloseReason.from("complete"))
        assertEquals(FrameEvent.CloseReason.CLOSE, FrameEvent.CloseReason.from("bored"))
    }

    @Test fun aReplacedPageBeforeShowAndADeadRendererBeforeShowFailWithTheLoadCode() {
        send("ad.loaded")
        events.clear()
        bridge.webView.webViewClient.onPageStarted(bridge.webView, "https://frames.test/replaced", null)
        assertEquals(500, (events.single() as FrameEvent.Error).code)
        events.clear()
        val loading = bridge(isVideo = true)
        loading.webView.webViewClient.onRenderProcessGone(loading.webView, io.mockk.mockk(relaxed = true))
        assertEquals(400, (events.single() as FrameEvent.Error).code)
        events.clear()
        val loaded = bridge(isVideo = true)
        send("ad.loaded", to = loaded)
        events.clear()
        loaded.webView.webViewClient.onRenderProcessGone(loaded.webView, io.mockk.mockk(relaxed = true))
        assertEquals("loaded, never shown: still the load error code, not 405", 400, (events.single() as FrameEvent.Error).code)
        assertEquals(FramePhase.ENDED, loaded.state)
    }

    @Test fun theLoadTimeoutCountsOnlyTimeInFrontAndSparesALoadedPage() {
        FrameBridge.loadTimeoutMillis = 500
        Environment.now = { android.os.SystemClock.uptimeMillis() } // Robolectric's clock moves with the looper
        var inFront = false
        Environment.isAppInForeground = { inFront }
        bridge.load()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertTrue("in the background the wait does not run", events.isEmpty())
        inFront = true
        // The stretch across the return does not count (in front at one end only); the next 500 ms do.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(800))
        assertEquals(FrameBridge.FailureCause.LOAD_TIMEOUT, bridge.failureCause)
        events.clear()
        val ready = bridge(isVideo = false)
        ready.load()
        send("ad.loaded", to = ready)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(listOf<FrameEvent>(FrameEvent.Loaded), events)
    }

    @Test fun aFrozenStretchIsNotLoadTime() {
        // In front, then in the background, frozen for a minute (the cached-app freezer), then in front again.
        Environment.now = { android.os.SystemClock.elapsedRealtime() }
        var inFront = true
        Environment.isAppInForeground = { inFront }
        bridge.load()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        inFront = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        // Frozen: no tick runs, the clocks move on.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(60_000))
        inFront = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals("a minute begun in the background is not ten seconds of loading", FramePhase.LOADING, bridge.state)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_500))
        assertEquals("ten seconds in front are", FrameBridge.FailureCause.LOAD_TIMEOUT, bridge.failureCause)
    }

    @Test fun aStretchInTheBackgroundBetweenTwoTicksInFrontIsNotLoadTime() {
        // In front at one tick, then in the background and frozen for a minute, back in front before the next
        // tick: both ticks find the app in front, and only the time since its return counts.
        Environment.now = { android.os.SystemClock.elapsedRealtime() }
        var cameToFront = Long.MIN_VALUE
        Environment.cameToFrontAt = { cameToFront }
        Environment.isAppInForeground = { true }
        bridge.load()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(60_000))
        cameToFront = Environment.now()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        assertEquals("a minute in the background is not ten seconds of loading", FramePhase.LOADING, bridge.state)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_000))
        assertEquals("ten seconds in front are", FrameBridge.FailureCause.LOAD_TIMEOUT, bridge.failureCause)
    }

    @Test fun showIsIgnoredUnlessThePageIsLoaded() {
        bridge.show()
        assertEquals(FramePhase.LOADING, bridge.state)
        shown()
        bridge.show()
        assertEquals(FramePhase.SHOWN, bridge.state)
    }

    @Test fun anInDocumentMoveStaysAndAnyOtherNavigationIsDecidedByOrigin() {
        shown()
        assertEquals(FrameNavigation.NavigationDecision.ALLOW, bridge.navigationDecision("$url#step2", true, url))
        assertEquals(FrameNavigation.NavigationDecision.CANCEL, bridge.navigationDecision("https://frames.test/other#a", true, url))
        assertEquals(FrameNavigation.NavigationDecision.CANCEL, bridge.navigationDecision("about:blank", true, null))
        assertEquals(FrameNavigation.NavigationDecision.EXTERNAL, bridge.navigationDecision("https://brand.test/#a", true, url))
    }

    @Test fun theBridgeIgnoresWhatDoesNotFitItsOrderAndReportsABrokenEnvelopeOnce() {
        val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = true)
        val sent = mutableListOf<String>()
        bridge.onSend = { type, _ -> sent += type }
        val events = mutableListOf<FrameEvent>()
        bridge.onEvent = { events += it }
        val page = Origin.of("https://frames.test")
        bridge.show() // before loaded: ignored
        assertTrue(sent.isEmpty())
        bridge.receive("not json", page, true)
        bridge.receive("""{"type":"ad.loaded"}""", page, true)
        assertEquals("one report for a broken page", listOf("frame.protocol_violation"), ErrorReporter.bufferedCodes)
        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", page, true)
        bridge.show(so.kontext.video.internal.payloads.AdPayload.Size(320, 80))
        bridge.exposure(0.5, 0.0, 0.0, 10.0, 10.0)
        bridge.receive("""{"adId":"ad-1","type":"something.new"}""", page, true)
        bridge.receive("""{"adId":"ad-1","type":"video.progress","offset":"soon"}""", page, true)
        bridge.receive("""{"adId":"ad-1","type":"video.mute","playhead":1234}""", page, true)
        bridge.receive("""{"adId":"ad-1","type":"video.start","playhead":0}""", page, true)
        bridge.receive("""{"adId":"ad-1","type":"video.complete","playhead":30000}""", page, true)
        assertEquals(listOf("ad.show", "ad.exposure"), sent)
        assertEquals(4, events.size)
        assertEquals(FrameEvent.VideoEvent.MUTE, (events[1] as FrameEvent.Video).event)
        assertEquals(1234, (events[1] as FrameEvent.Video).playheadMillis)
        bridge.hide()
        assertEquals("ad.hide", sent.last())
        assertEquals(FramePhase.ENDED, bridge.state)
        bridge.receive("""{"adId":"ad-1","type":"video.start"}""", page, true) // ended: nothing
        assertEquals(4, events.size)
    }

    @Test fun theVideoCountsOnlyAfterVideoStartAndADroppedMessageIsReportedOnce() {
        val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = true)
        val events = mutableListOf<FrameEvent>()
        bridge.onEvent = { events += it }
        val page = Origin.of("https://frames.test")
        fun send(type: String, fields: String = "") = bridge.receive("""{"adId":"ad-1","type":"$type"$fields}""", page, true)
        send("ad.loaded")
        bridge.show()
        events.clear()
        for (early in listOf("video.firstQuartile", "video.midpoint", "video.thirdQuartile", "video.pause", "video.resume", "video.complete")) send(early)
        send("video.progress", ""","offset":1000""")
        assertTrue("nothing of the video before its start: $events", events.isEmpty())
        assertEquals("one report, its detail named", listOf("frame.protocol_violation"), ErrorReporter.bufferedCodes)
        assertEquals(listOf("video.beforeStart"), ErrorReporter.bufferedDetails)
        send("video.mute")
        send("video.unmute")
        assertEquals("the viewer's mute and unmute go before the start", listOf(FrameEvent.VideoEvent.MUTE, FrameEvent.VideoEvent.UNMUTE), events.map { (it as FrameEvent.Video).event })
        send("video.start")
        send("video.firstQuartile")
        send("video.progress", ""","offset":1000""")
        send("video.complete")
        assertEquals(listOf("Video", "Video", "Video", "Video", "Progress", "Complete"), events.map { it::class.simpleName })
        val skipping = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = true)
        val skipped = mutableListOf<FrameEvent>()
        skipping.onEvent = { skipped += it }
        skipping.receive("""{"adId":"ad-1","type":"ad.loaded"}""", page, true)
        skipping.show()
        skipping.receive("""{"adId":"ad-1","type":"video.skip","playhead":0}""", page, true)
        assertTrue("a skip goes before the start", skipped.last() is FrameEvent.Skip)
    }

    @Test fun aLinearAdsPlayheadAndProgressStopAtItsDurationAndASecond() {
        val page = Origin.of("https://frames.test")
        fun shownBridge(duration: Long?): Pair<FrameBridge, MutableList<FrameEvent>> {
            val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", true, duration)
            val events = mutableListOf<FrameEvent>()
            bridge.onEvent = { events += it }
            bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", page, true)
            bridge.show()
            bridge.receive("""{"adId":"ad-1","type":"video.start","playhead":0}""", page, true)
            events.clear()
            return bridge to events
        }
        val (linear, events) = shownBridge(30_000)
        linear.receive("""{"adId":"ad-1","type":"video.progress","offset":50000,"playhead":1e12}""", page, true)
        val progress = events.single() as FrameEvent.Progress
        assertEquals("the offset stops at the end and a second", 31_000L, progress.offsetMillis)
        assertEquals("so does the playhead", 31_000L, progress.playheadMillis)
        linear.receive("""{"adId":"ad-1","type":"video.complete","playhead":30500}""", page, true)
        assertEquals("within the grace: as sent", 30_500L, (events.last() as FrameEvent.Complete).playheadMillis)
        val (overlay, overlayEvents) = shownBridge(null)
        overlay.receive("""{"adId":"ad-1","type":"video.complete","playhead":1e12}""", page, true)
        assertEquals("without a duration: a day", 86_400_000L, (overlayEvents.last() as FrameEvent.Complete).playheadMillis)
    }

    @Test fun aPageErrorAfterShowDefaultsTo405AndBeforeToTheLoadCode() {
        val page = Origin.of("https://frames.test")
        val video = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/a", isVideo = true)
        var code = 0
        video.onEvent = { if (it is FrameEvent.Error) code = it.code }
        video.receive("""{"adId":"ad-1","type":"ad.error"}""", page, true)
        assertEquals("a video's load code", 400, code)
        val shown = FrameBridge(Environment.context!!, "ad-2", "https://frames.test/b", isVideo = false)
        shown.onEvent = { if (it is FrameEvent.Error) code = it.code }
        shown.receive("""{"adId":"ad-2","type":"ad.loaded"}""", page, true)
        shown.show()
        shown.receive("""{"adId":"ad-2","type":"ad.error"}""", page, true)
        assertEquals(405, code)
    }

    @Test fun aBinaryMessageIsDroppedInEveryStateAndCountsAsOneBrokenEnvelope() {
        val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = false)
        val events = mutableListOf<FrameEvent>()
        bridge.onEvent = { events += it }
        val page = Origin.of("https://frames.test")
        val binary = WebMessageCompat(byteArrayOf(1, 2, 3))
        bridge.receive(binary, Origin.of("https://elsewhere.test"), true)
        bridge.receive(binary, page, false)
        assertTrue("from another sender or frame: dropped, not reported", ErrorReporter.bufferedCodes.isEmpty())
        bridge.receive(binary, page, true) // loading
        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", page, true)
        bridge.show()
        bridge.receive(binary, page, true) // shown
        bridge.receive(WebMessageCompat("""{"adId":"ad-1","type":"ad.close"}"""), page, true)
        bridge.receive(binary, page, true) // ended
        assertEquals("one report for a broken page", listOf("frame.protocol_violation"), ErrorReporter.bufferedCodes)
        assertEquals("text still goes through", listOf("Loaded", "Close"), events.map { it::class.simpleName })
    }
}
