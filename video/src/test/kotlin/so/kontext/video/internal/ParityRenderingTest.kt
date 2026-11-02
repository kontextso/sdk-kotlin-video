package so.kontext.video.internal

import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.webkit.WebResourceRequest
import android.widget.FrameLayout
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.SdkTest
import so.kontext.video.VideoAd
import so.kontext.video.internal.rendering.ActiveTimeClock
import so.kontext.video.internal.rendering.AdHostView
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.FrameEvent
import so.kontext.video.internal.rendering.ViewabilityMeter
import so.kontext.video.internal.support.Environment
import java.lang.ref.WeakReference

/**
 * The Swift suite's rules for the frame bridge, the viewability meter and the on-screen clock: which message
 * counts in which state and from whom, what the SDK sends, the dwell and the verdicts, and what the clock follows.
 */
@RunWith(RobolectricTestRunner::class)
class ParityRenderingTest : SdkTest() {
    private val url = "https://frames.test/v2/frame/ad-1"
    private val origin = FrameBridge.Origin.of(url)
    private val received = mutableListOf<FrameEvent>()
    private var now = 1_000_000L

    @Before fun freeze() {
        Environment.now = { now }
        FrameBridge.loadTimeoutMillis = 10_000
    }

    @After fun restore() {
        FrameBridge.loadTimeoutMillis = 10_000
        ViewabilityMeter.intervalMillis = 250
    }

    // region The bridge

    private fun bridge(isVideo: Boolean = false, at: String = url) =
        FrameBridge(Environment.context!!, "ad-1", at, isVideo).also { it.onEvent = { event -> received += event } }

    private fun FrameBridge.send(type: String, fields: String = "", from: FrameBridge.Origin? = origin) =
        receive("""{"adId":"ad-1","type":"$type"${if (fields.isEmpty()) "" else ", $fields"}}""", from, true)

    private fun FrameBridge.shown() = apply {
        send("ad.loaded")
        show()
        received.clear()
    }

    private fun request(target: String): WebResourceRequest = mockk {
        every { this@mockk.url } returns Uri.parse(target)
        every { isForMainFrame } returns true
    }

    private fun FrameBridge.navigate(target: String) = webView.webViewClient.shouldOverrideUrlLoading(webView, request(target))

    @Test fun anotherSchemeIsAnotherOrigin() {
        val bridge = bridge()
        bridge.send("ad.loaded", from = FrameBridge.Origin.of("http://frames.test"))
        assertTrue(received.isEmpty())
        assertEquals(FrameBridge.State.LOADING, bridge.state)
    }

    @Test fun theDefaultPortIsTheSameOriginAndAnyOtherIsNot() {
        bridge().send("ad.loaded", from = FrameBridge.Origin.of("https://frames.test:8443"))
        assertTrue("another port", received.isEmpty())
        bridge().send("ad.loaded", from = FrameBridge.Origin.of("https://frames.test:443"))
        assertEquals("the default port spelled out", 1, received.size)
        val local = bridge(at = "http://localhost:5175/frame/ad-1")
        local.send("ad.loaded", from = FrameBridge.Origin.of("http://localhost"))
        assertEquals("localhost without its port is another origin", 1, received.size)
        local.send("ad.loaded", from = FrameBridge.Origin.of("http://localhost:5175"))
        assertEquals(2, received.size)
        bridge(at = "kontext-test://frames/frame/ad-1").send("ad.loaded", from = FrameBridge.Origin.of("kontext-test://frames"))
        assertEquals("a custom scheme without a port", 3, received.size)
    }

    @Test fun everyVideoMomentCarriesItsNameAndPlayhead() {
        val bridge = bridge(isVideo = true).shown()
        for (event in FrameEvent.VideoEvent.entries) {
            bridge.send("video.${event.wire}", "\"playhead\": 1234")
            val video = received.last() as FrameEvent.Video
            assertEquals(event, video.event)
            assertEquals(1234L, video.playheadMillis)
        }
        val count = received.size
        bridge.send("video.buffering", "\"playhead\": 1234")
        assertEquals("a video moment the SDK does not know is ignored", count, received.size)
    }

    @Test fun closeEndsThePageAndAMissingOrUnknownReasonIsAClose() {
        val bridge = bridge().shown()
        bridge.send("ad.close")
        assertEquals(FrameEvent.CloseReason.CLOSE, (received.single() as FrameEvent.Close).reason)
        assertEquals(FrameBridge.State.ENDED, bridge.state)
        bridge.send("ad.close")
        assertEquals("nothing after ended", 1, received.size)
        bridge().shown().send("ad.close", "\"reason\": \"swipe\"")
        assertEquals(FrameEvent.CloseReason.CLOSE, (received.last() as FrameEvent.Close).reason)
    }

    @Test fun anErrorOnScreenKeepsThePagesCode() {
        bridge(isVideo = true).shown().send("ad.error", "\"code\": 403")
        assertEquals(403, (received.single() as FrameEvent.Error).code)
    }

    @Test fun aVideoPagesLoadTimeoutFailsWithTheLinearGeneralCode() {
        FrameBridge.loadTimeoutMillis = 500
        Environment.isAppInForeground = { true }
        val bridge = bridge(isVideo = true)
        bridge.load()
        idle(1_000)
        assertEquals(400, (received.single() as FrameEvent.Error).code)
        assertEquals(FrameBridge.State.ENDED, bridge.state)
    }

    @Test fun aClickKeepsItsFallbackAndDefaultsTheArea() {
        val bridge = bridge().shown()
        bridge.noteTouch()
        bridge.send("ad.click", "\"url\": \"brandapp://offer\", \"fallbackUrl\": \"https://brand.test/offer\"")
        val click = received.single() as FrameEvent.Click
        assertEquals("deep links are allowed", "brandapp://offer", click.url)
        assertEquals("https://brand.test/offer", click.fallbackUrl)
        assertEquals(VideoAd.Click.Area.Creative, click.area)
    }

    @Test fun oneTouchOpensOneUrlWhicheverWayItLeaves() {
        val bridge = bridge().shown()
        bridge.noteTouch()
        bridge.send("ad.click", "\"url\": \"https://brand.test/offer\"")
        assertTrue(bridge.navigate("https://brand.test/offer"))
        bridge.send("ad.open", "\"url\": \"https://adchoices.test\", \"purpose\": \"adChoices\"")
        assertEquals("the tap was spent by the click", 1, received.size)
        bridge.noteTouch()
        bridge.navigate("https://brand.test/other")
        assertEquals("a new tap opens again", "https://brand.test/other", (received.last() as FrameEvent.Click).url)
        assertEquals(2, received.size)
    }

    @Test fun aRedirectWithTheExplicitDefaultPortIsTheSameOrigin() {
        val bridge = bridge()
        val decide = { target: String -> bridge.navigationDecision(target, true, null) }
        assertEquals(FrameBridge.NavigationDecision.ALLOW, decide("https://frames.test:443/v2/frame/ad-1/next"))
        assertEquals(FrameBridge.NavigationDecision.ALLOW, decide("https://frames.test/other"))
        assertEquals(FrameBridge.NavigationDecision.EXTERNAL, decide("https://frames.test:8443/v2/frame/ad-1"))
        assertEquals(FrameBridge.NavigationDecision.EXTERNAL, decide("http://frames.test/v2/frame/ad-1"))
        assertEquals(FrameBridge.NavigationDecision.EXTERNAL, decide("https://evil.test/v2/frame/ad-1"))
        val local = bridge(at = "http://localhost:5175/frame/ad-1")
        assertEquals(FrameBridge.NavigationDecision.ALLOW, local.navigationDecision("http://localhost:5175/x", true, null))
        assertEquals(FrameBridge.NavigationDecision.EXTERNAL, local.navigationDecision("http://localhost/x", true, null))
    }

    @Test fun aTouchTooLongAgoAuthorizesNothing() {
        val bridge = bridge().shown()
        bridge.noteTouch()
        now += FrameBridge.TOUCH_WINDOW_MILLIS + 500
        bridge.send("ad.click", "\"url\": \"https://brand.test\"")
        bridge.navigate("https://brand.test")
        assertTrue("an expired touch opens nothing", received.isEmpty())
        bridge.noteTouch()
        now += FrameBridge.TOUCH_WINDOW_MILLIS - 100
        bridge.send("ad.click", "\"url\": \"https://brand.test\"")
        assertEquals("a touch within the window still does", 1, received.size)
    }

    @Test fun whileLoadingABlankPageGoesThroughAndOnceReadyTheSameFragmentStays() {
        val bridge = bridge()
        assertEquals(FrameBridge.NavigationDecision.ALLOW, bridge.navigationDecision("about:blank", true, url))
        bridge.shown()
        val anchored = "$url#endcard"
        assertEquals(FrameBridge.NavigationDecision.ALLOW, bridge.navigationDecision(anchored, true, anchored))
        assertEquals(FrameBridge.NavigationDecision.CANCEL, bridge.navigationDecision(url, true, url))
        assertEquals(FrameBridge.NavigationDecision.CANCEL, bridge.navigationDecision("about:blank", true, url))
        assertEquals(FrameBridge.NavigationDecision.ALLOW, bridge.navigationDecision("https://anything.test/creative", false, url))
    }

    @Test fun anOpenCarriesAnUnfamiliarPurposeUnchanged() {
        val bridge = bridge().shown()
        bridge.noteTouch()
        bridge.send("ad.open", "\"url\": \"https://privacy.test\", \"purpose\": \"whyThisAd\"")
        val open = received.single() as FrameEvent.Open
        assertEquals("whyThisAd", open.purpose)
        assertEquals("https://privacy.test", open.url)
    }

    @Test fun aClickWithANumberOrAnEmptyUrlIsDropped() {
        val bridge = bridge().shown()
        bridge.noteTouch()
        bridge.send("ad.click", "\"url\": 42")
        bridge.send("ad.click", "\"url\": \"\"")
        assertTrue(received.isEmpty())
    }

    @Test fun aPageNeverShownGetsNoHide() {
        val bridge = bridge()
        val sent = mutableListOf<String>()
        bridge.onSend = { type, _ -> sent += type }
        bridge.send("ad.loaded")
        bridge.hide()
        assertTrue(sent.isEmpty())
        assertEquals(FrameBridge.State.ENDED, bridge.state)
    }

    @Test fun exposureIsSentOnlyWhileShownInTheProtocolsEnvelope() {
        val bridge = bridge()
        val sent = mutableListOf<JsonObject>()
        bridge.onSend = { type, message -> if (type == "ad.exposure") sent += message }
        bridge.exposure(1.0, 0.0, 0.0, 0.0, 0.0)
        bridge.send("ad.loaded")
        bridge.exposure(1.0, 0.0, 0.0, 0.0, 0.0)
        assertTrue("not before ad.show", sent.isEmpty())
        bridge.show()
        bridge.exposure(0.5, 1.0, 2.0, 3.0, 4.0)
        val message = sent.single()
        assertEquals("ad-1", message["adId"]!!.jsonPrimitive.content)
        assertEquals("ad.exposure", message["type"]!!.jsonPrimitive.content)
        assertEquals("the payload sits beside adId and type", 0.5, message["exposedPercentage"]!!.jsonPrimitive.double, 0.0)
        val rectangle = message["visibleRectangle"]!!.jsonObject
        assertEquals(1.0, rectangle["x"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3.0, rectangle["width"]!!.jsonPrimitive.double, 0.0)
        bridge.hide()
        bridge.exposure(1.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals("not after the end", 1, sent.size)
    }

    @Test fun endingTakesTheWebViewOutOfWhereItWas() {
        val bridge = bridge()
        val parent = FrameLayout(Environment.context!!)
        parent.addView(bridge.webView)
        bridge.hide()
        assertNull("nothing stays in a view after the ad is gone", bridge.webView.parent)
    }

    @Test fun theWebViewIsLockedDown() {
        val settings = bridge().webView.settings
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse("a video plays without a tap", settings.mediaPlaybackRequiresUserGesture)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.supportZoom())
        assertTrue("target=_blank comes to onCreateWindow, where it is refused", settings.supportMultipleWindows())
    }

    // endregion

    // region The viewability meter

    private class Sink(meter: ViewabilityMeter) {
        val exposures = mutableListOf<Double>()
        val rectangles = mutableListOf<Rect>()
        var verdict: ViewabilityMeter.Verdict? = null
        var detached = 0

        init {
            meter.onExposure = { percentage, rectangle ->
                exposures += percentage
                rectangles += rectangle
            }
            meter.onVerdict = { verdict = it }
            meter.onDetached = { detached += 1 }
        }
    }

    /** Ticks only when the test calls them: the timer is set too far apart to fire. */
    private fun frozenMeter(view: View, requiredMillis: Long = 1_000): Pair<ViewabilityMeter, Sink> {
        ViewabilityMeter.intervalMillis = 1_000_000
        val meter = ViewabilityMeter(view, requiredMillis)
        return meter to Sink(meter)
    }

    /** A view at [left], [top] in a 320×400 container, which clips it. */
    private fun view(left: Int = 0, top: Int = 0, width: Int = 200, height: Int = 100, into: FrameLayout = container()): View {
        val view = View(into.context)
        into.addView(view)
        view.layout(left, top, left + width, top + height)
        return view
    }

    private fun View.windowVisibility(visibility: Int) =
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(this, "mAttachInfo"), "mWindowVisibility", visibility)

    private fun ViewabilityMeter.tick(at: Long) {
        now = at
        tick()
    }

    @Test fun aFullyVisibleViewBecomesViewableAfterTheDwell() {
        val (meter, sink) = frozenMeter(view())
        meter.start()
        assertEquals(listOf(1.0), sink.exposures)
        meter.tick(now + 1_000)
        assertEquals(ViewabilityMeter.Verdict.VIEWABLE, sink.verdict)
        assertEquals(ViewabilityMeter.Verdict.VIEWABLE, meter.verdict)
        meter.stop()
    }

    @Test fun nothingIsReportedBeforeTheViewIsLaidOutInAWindowThenARealZeroIs() {
        val container = container()
        val view = View(container.context)
        container.addView(view) // attached, not laid out yet: the first tick runs before the first layout pass
        val (meter, sink) = frozenMeter(view)
        meter.start()
        meter.tick(now + 100)
        assertEquals("no exposure 0 before the first layout", emptyList<Double>(), sink.exposures)
        view.layout(0, 0, 200, 100)
        meter.tick(now + 100)
        assertEquals(listOf(1.0), sink.exposures)
        view.windowVisibility(View.GONE) // covered by another Activity
        meter.tick(now + 100)
        container.removeView(view)
        view.layout(0, 0, 200, 100)
        meter.tick(now + 100)
        assertEquals("a real 0 later is reported", listOf(1.0, 0.0), sink.exposures)
        meter.stop()
    }

    @Test fun aViewNotYetInAWindowReportsNothing() {
        val view = View(Environment.context!!) // a linear ad's WebView before its Activity's window is attached
        val (meter, sink) = frozenMeter(view)
        meter.start()
        assertEquals(emptyList<Double>(), sink.exposures)
        meter.stop()
    }

    @Test fun aViewInAWindowNotShownIsExposedZeroUntilItComesBack() {
        val view = view()
        view.windowVisibility(View.GONE) // the Activity stopped: the app is in the background, or another covers it
        val (meter, sink) = frozenMeter(view)
        meter.start()
        meter.tick(now + 5_000)
        assertEquals("the page hears exposure 0", listOf(0.0), sink.exposures)
        assertNull("and no viewability clock runs", sink.verdict)
        view.windowVisibility(View.VISIBLE)
        meter.tick(now + 100)
        meter.tick(now + 1_000)
        assertEquals("on return the ad counts again", ViewabilityMeter.Verdict.VIEWABLE, sink.verdict)
        meter.stop()
    }

    @Test fun measuringGoesOnAfterTheVerdictAndAViewLeftOutIsReportedOnEveryTickFromTheSecond() {
        val container = container()
        val view = view(into = container)
        val (meter, sink) = frozenMeter(view)
        meter.start()
        meter.tick(now + 1_000)
        assertEquals(ViewabilityMeter.Verdict.VIEWABLE, sink.verdict)
        container.removeView(view)
        meter.tick(now + 100)
        assertEquals("the page still hears exposure after the verdict", 0.0, sink.exposures.last(), 0.0)
        assertEquals("one tick of grace", 0, sink.detached)
        meter.tick(now + 100)
        meter.tick(now + 100)
        assertEquals(2, sink.detached)
        meter.stop()
    }

    @Test fun halfOnScreenIsHalfExposedAndCounts() {
        val (meter, sink) = frozenMeter(view(left = 220)) // 100 of 200 px inside the 320 px container
        meter.start()
        assertEquals(listOf(0.5), sink.exposures)
        meter.tick(now + 1_000)
        assertEquals("50 % counts", ViewabilityMeter.Verdict.VIEWABLE, sink.verdict)
        meter.stop()
    }

    @Test fun aQuarterOnScreenNeverBecomesViewable() {
        val (meter, sink) = frozenMeter(view(left = 270))
        meter.start()
        meter.tick(now + 5_000)
        assertEquals(listOf(0.25), sink.exposures)
        assertNull(sink.verdict)
        meter.stop()
        assertEquals(ViewabilityMeter.Verdict.NOT_VIEWABLE, sink.verdict)
    }

    @Test fun stoppingBeforeTheDwellIsNotViewable() {
        val (meter, sink) = frozenMeter(view(), requiredMillis = 5_000)
        meter.start()
        meter.tick(now + 60)
        meter.stop()
        assertEquals(ViewabilityMeter.Verdict.NOT_VIEWABLE, sink.verdict)
    }

    @Test fun aHiddenAncestorHidesTheAd() {
        val container = container()
        val parent = FrameLayout(container.context)
        container.addView(parent)
        parent.layout(0, 0, 320, 400)
        val (meter, sink) = frozenMeter(view(into = parent))
        meter.start()
        assertEquals(listOf(1.0), sink.exposures)
        parent.visibility = View.INVISIBLE
        meter.tick(now + 100)
        assertEquals(listOf(1.0, 0.0), sink.exposures)
        meter.stop()
    }

    @Test fun aScaledAncestorDoesNotShrinkTheShare() {
        val container = container()
        val player = FrameLayout(container.context)
        container.addView(player)
        player.layout(0, 0, 640, 800) // twice the container: only at half size is it on screen in full
        player.pivotX = 0f
        player.pivotY = 0f
        player.scaleX = 0.5f // a mini player
        player.scaleY = 0.5f
        val (meter, sink) = frozenMeter(view(left = 400, top = 500, into = player))
        meter.start()
        assertEquals("the whole ad is visible, however small it is drawn", 1.0, sink.exposures.single(), 0.001)
        meter.stop()
    }

    @Test fun stopIsFinalAndStartDoesNotRestart() {
        ViewabilityMeter.intervalMillis = 20
        val view = view()
        val meter = ViewabilityMeter(view, 50)
        val sink = Sink(meter)
        meter.start()
        meter.stop()
        assertEquals(ViewabilityMeter.Verdict.NOT_VIEWABLE, sink.verdict)
        meter.start()
        view.layout(220, 0, 420, 100) // a tick would report the new exposure
        idle(200)
        assertEquals("no tick after stop", 1, sink.exposures.size)
    }

    @Test fun aGapBetweenTicksRestartsTheDwell() {
        val (meter, sink) = frozenMeter(view(), requiredMillis = 500)
        ViewabilityMeter.intervalMillis = 20
        meter.start()
        meter.tick(now + 20)
        meter.tick(now + 3_000) // the main thread stalled, or the app was suspended: nothing was measured meanwhile
        assertNull("three seconds passed on the clock, but not continuously on screen", sink.verdict)
        meter.stop()
        assertEquals(ViewabilityMeter.Verdict.NOT_VIEWABLE, sink.verdict)
    }

    @Test fun aNewPlaceAtTheSamePercentageIsReported() {
        val view = view()
        val (meter, sink) = frozenMeter(view)
        meter.start()
        view.layout(220, 0, 420, 100) // the right half outside: 50 %
        meter.tick(now + 100)
        view.layout(-100, 0, 100, 100) // the left half outside: still 50 %, another rectangle
        meter.tick(now + 100)
        assertEquals(listOf(1.0, 0.5, 0.5), sink.exposures)
        assertTrue("the page hears where the visible part is", sink.rectangles[1] != sink.rectangles[2])
        meter.stop()
    }

    @Test fun exposureIsReportedOnlyWhenItChanges() {
        val view = view()
        val (meter, sink) = frozenMeter(view, requiredMillis = 5_000)
        meter.start()
        meter.tick(now + 100)
        meter.tick(now + 100)
        assertEquals(listOf(1.0), sink.exposures)
        view.layout(220, 0, 420, 100)
        meter.tick(now + 100)
        assertEquals(listOf(1.0, 0.5), sink.exposures)
        meter.stop()
    }

    @Test fun aHiddenViewIsNotExposed() {
        val view = view()
        view.visibility = View.INVISIBLE
        val (meter, sink) = frozenMeter(view, requiredMillis = 50)
        meter.start()
        meter.tick(now + 150)
        assertEquals(listOf(0.0), sink.exposures)
        assertNull(sink.verdict)
        meter.stop()
    }

    @Test fun leavingTheScreenRestartsTheDwell() {
        val view = view()
        val (meter, sink) = frozenMeter(view, requiredMillis = 150)
        meter.start()
        meter.tick(now + 80)
        view.layout(1_000, 0, 1_200, 100)
        meter.tick(now + 60)
        view.layout(0, 0, 200, 100)
        meter.tick(now + 50) // back on screen: the dwell starts here
        meter.tick(now + 50)
        meter.tick(now + 50)
        assertNull("the dwell starts over after leaving the screen", sink.verdict)
        meter.tick(now + 50)
        assertEquals(ViewabilityMeter.Verdict.VIEWABLE, sink.verdict)
        meter.stop()
    }

    @Test fun aViewThatIsGoneIsUndetermined() {
        val (meter, sink) = frozenMeter(view(), requiredMillis = 5_000)
        meter.start()
        ReflectionHelpers.getField<WeakReference<View>>(meter, "view").clear()
        meter.tick(now + 50)
        meter.stop()
        assertEquals(ViewabilityMeter.Verdict.VIEW_UNDETERMINED, sink.verdict)
    }

    @Test fun theVerdictIsFinal() {
        val (meter, sink) = frozenMeter(view(), requiredMillis = 50)
        meter.start()
        meter.tick(now + 50)
        meter.stop()
        meter.start()
        assertEquals(ViewabilityMeter.Verdict.VIEWABLE, sink.verdict)
        assertEquals(ViewabilityMeter.Verdict.VIEWABLE, meter.verdict)
    }

    @Test fun theDwellCountsToTheBoundaryExactly() {
        val (meter, sink) = frozenMeter(view())
        val start = now
        meter.start()
        meter.tick(start + 999)
        assertNull("just before", sink.verdict)
        meter.tick(start + 1_000)
        assertEquals("exactly at the dwell", ViewabilityMeter.Verdict.VIEWABLE, sink.verdict)
        meter.stop()
    }

    @Test fun aPauseBetweenTwoTicksRestartsTheDwell() {
        val (meter, sink) = frozenMeter(view(), requiredMillis = 2_000)
        val start = now
        meter.start() // playing from the start
        now = start + 1_050
        meter.countsTime = false // paused...
        now = start + 1_100
        meter.countsTime = true // ...and playing again before any tick saw the pause
        meter.tick(start + 2_000)
        assertNull("two seconds since the start, but not two continuous seconds of playback", sink.verdict)
        meter.tick(start + 3_999)
        assertNull(sink.verdict)
        meter.tick(start + 4_000)
        assertEquals("two seconds from the first tick after the pause", ViewabilityMeter.Verdict.VIEWABLE, sink.verdict)
        meter.stop()
    }

    @Test fun aRotatedViewHalfOutsideIsMeasuredOnItsShape() {
        // A 100×100 square turned 45°, its centre 20 px beyond the container's left edge: 25.7 % of it shows.
        // Its bounding box would claim three quarters.
        val view = view(left = -70, top = 150, width = 100, height = 100)
        view.rotation = 45f
        val (meter, sink) = frozenMeter(view)
        meter.start()
        val share = sink.exposures.single()
        assertTrue("$share", share in 0.25..0.27)
        meter.tick(now + 5_000)
        assertNull("never viewable at a quarter", sink.verdict)
        meter.stop()
    }

    @Test fun justUnderHalfRoundsToHalfForThePageButNeverQualifies() {
        // 124 of 250 px on screen: 49.6 %.
        val (meter, sink) = frozenMeter(view(left = 196, width = 250))
        meter.start()
        assertEquals("reported rounded", listOf(0.5), sink.exposures)
        meter.tick(now + 10_000)
        assertNull("the rule compares the real share", sink.verdict)
        meter.stop()
    }

    @Test fun aZeroSizedViewOrATransparentWindowIsNotExposed() {
        val (empty, emptySink) = frozenMeter(view(width = 0, height = 0))
        empty.start()
        assertEquals("a view never laid out with an area has had no first measurement", emptyList<Double>(), emptySink.exposures)
        empty.stop()
        assertEquals(ViewabilityMeter.Verdict.NOT_VIEWABLE, emptySink.verdict)
        val view = view()
        view.rootView.alpha = 0f
        val (meter, sink) = frozenMeter(view)
        meter.start()
        assertEquals(listOf(0.0), sink.exposures)
        meter.stop()
    }

    // endregion

    // region The on-screen clock

    @Test fun anElapsedDurationIsReportedOnceWhateverTheWindowDoesAfter() {
        Environment.reset() // the real clock: the duration runs on the looper
        var elapsed = 0
        val clock = ActiveTimeClock(100, "clock-1") { elapsed += 1 }
        clock.start(visible = true)
        idle(200)
        assertEquals(1, elapsed)
        // The window hides and comes back after the duration ran out: the rest of the duration is zero.
        repeat(2) {
            clock.windowVisibilityChanged(false)
            clock.windowVisibilityChanged(true)
        }
        idle(500)
        assertEquals("once, not again on every return", 1, elapsed)
        clock.stop()
    }

    @Test fun theAdsClockFollowsItsOwnWindowNotTheApp() {
        val ad = overlay(duration = "60000")
        val container = container()
        ad.show(container)
        val clock = ad.clock!!
        assertTrue(clock.isCounting)
        Environment.isAppInForeground = { false } // another Activity of the app is what stopped
        assertTrue("its window still shows", clock.isCounting)
        val host = container.getChildAt(0) as AdHostView
        host.dispatchWindowVisibilityChanged(View.GONE)
        assertFalse("frozen with its own window", clock.isCounting)
        val frozen = clock.elapsedMillis
        now += 30_000
        assertEquals(frozen, clock.elapsedMillis)
        host.dispatchWindowVisibilityChanged(View.VISIBLE)
        assertTrue("counting again when the window is back", clock.isCounting)
        ad.dismiss()
    }

    // endregion
}
