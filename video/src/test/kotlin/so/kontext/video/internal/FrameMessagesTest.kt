package so.kontext.video.internal

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.FrameEvent
import so.kontext.video.internal.support.Environment
import java.time.Duration

/** The frame protocol from the page's side: what each message does in each state, and what is dropped. */
@RunWith(RobolectricTestRunner::class)
class FrameMessagesTest {
    private val url = "https://frames.test/v2/frame/ad-1"
    private val origin = FrameBridge.Origin.of(url)
    private val events = mutableListOf<FrameEvent>()
    private var now = 1_000_000L
    private lateinit var bridge: FrameBridge

    @Before fun make() {
        Environment.context = ApplicationProvider.getApplicationContext()
        Environment.now = { now }
        bridge = bridge(isVideo = false)
    }

    @After fun reset() {
        FrameBridge.loadTimeoutMillis = 10_000
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
        assertEquals(FrameBridge.State.LOADED, bridge.state)
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
        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", FrameBridge.Origin.of("https://evil.test"), true)
        bridge.receive(null, origin, true)
        bridge.receive("not json", origin, true)
        bridge.receive("[1, 2]", origin, true)
        bridge.receive("""{"adId":"ad-2","type":"ad.loaded"}""", origin, true)
        bridge.receive("""{"adId":"ad-1"}""", origin, true)
        send("ad.unknown")
        send("video.rewind")
        assertTrue(events.isEmpty())
        assertEquals(FrameBridge.State.LOADING, bridge.state)
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
        now += FrameBridge.TOUCH_WINDOW_MILLIS + 1
        send("ad.open", "\"url\": \"https://brand.test/why\"")
        assertEquals("a touch too long ago", 1, events.size)
        bridge.noteTouch()
        send("ad.open", "\"url\": \"https://brand.test/why\"")
        assertEquals("unknown", (events.last() as FrameEvent.Open).purpose)
    }

    @Test fun progressTakesOnlyAWholeNumberOfMillisecondsWithinADay() {
        shown()
        for (bad in listOf("\"1000\"", "1.5", "-1", "86400001", "null", "{}")) send("video.progress", "\"offset\": $bad")
        send("video.progress")
        assertTrue(events.isEmpty())
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
        assertEquals(FrameBridge.State.ENDED, bridge.state)
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
        assertEquals(FrameBridge.State.ENDED, loaded.state)
    }

    @Test fun theLoadTimeoutCountsOnlyTimeInFrontAndSparesALoadedPage() {
        FrameBridge.loadTimeoutMillis = 500
        var inFront = false
        Environment.isAppInForeground = { inFront }
        bridge.load()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertTrue("in the background the wait does not run", events.isEmpty())
        inFront = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(FrameBridge.FailureCause.LOAD_TIMEOUT, bridge.failureCause)
        events.clear()
        val ready = bridge(isVideo = false)
        ready.load()
        send("ad.loaded", to = ready)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertEquals(listOf<FrameEvent>(FrameEvent.Loaded), events)
    }

    @Test fun showIsIgnoredUnlessThePageIsLoaded() {
        bridge.show()
        assertEquals(FrameBridge.State.LOADING, bridge.state)
        shown()
        bridge.show()
        assertEquals(FrameBridge.State.SHOWN, bridge.state)
    }

    @Test fun originsSpellOutTheDefaultPort() {
        assertEquals("https://frames.test", FrameBridge.Origin.of("https://Frames.Test/x")!!.rule)
        assertEquals("http://frames.test", FrameBridge.Origin.of("http://frames.test:80/")!!.rule)
        assertEquals("https://frames.test:8443", FrameBridge.Origin.of("https://frames.test:8443/")!!.rule)
        assertEquals(-1, FrameBridge.Origin.of("ws://frames.test/")!!.port)
        assertNull("no scheme", FrameBridge.Origin.of("frames.test/x"))
        assertNull("no host", FrameBridge.Origin.of("mailto:someone@test"))
        assertNull("an empty host", FrameBridge.Origin.of("https:///path"))
    }

    @Test fun everyRefusedSchemeIsRefusedInAnyCase() {
        assertTrue(FrameBridge.isAllowed("https://brand.test"))
        assertTrue(FrameBridge.isAllowed("market://details?id=x"))
        assertTrue("an advertiser's app link", FrameBridge.isAllowed("brandapp://product/1"))
        val refused = listOf(
            "intent://x", "JavaScript:void(0)", "data:text/html,x", "file:///x", "blob:x", "about:blank", "content://x", "no scheme", "1http://x",
            "tel:+15550100", "TEL:1", "sms:+15550100", "smsto:1", "mms:1", "mmsto:1",
        )
        for (refused in refused) {
            assertFalse(refused, FrameBridge.isAllowed(refused))
        }
    }

    @Test fun anInDocumentMoveStaysAndAnyOtherNavigationIsDecidedByOrigin() {
        shown()
        assertEquals(FrameBridge.NavigationDecision.ALLOW, bridge.navigationDecision("$url#step2", true, url))
        assertEquals(FrameBridge.NavigationDecision.CANCEL, bridge.navigationDecision("https://frames.test/other#a", true, url))
        assertEquals(FrameBridge.NavigationDecision.CANCEL, bridge.navigationDecision("about:blank", true, null))
        assertEquals(FrameBridge.NavigationDecision.EXTERNAL, bridge.navigationDecision("https://brand.test/#a", true, url))
    }
}
