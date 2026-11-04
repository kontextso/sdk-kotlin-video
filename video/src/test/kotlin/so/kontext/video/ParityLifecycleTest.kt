package so.kontext.video

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.frame.handle
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.screen.AdHostView
import so.kontext.video.internal.screen.LinearAdActivity
import so.kontext.video.internal.support.Environment

/**
 * The ad lifecycle rules of the Swift suite that the other suites here left unchecked: what show places and
 * refuses, how an ad ends, the app's code running inside the SDK's, and the linear ad's screen. Kept in one
 * class so it reads against the Swift suite's files, hence its size.
 */
@Suppress("LargeClass")
@RunWith(RobolectricTestRunner::class)
class ParityLifecycleTest : SdkTest() {
    private inline fun <reified T : VideoAdException> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: VideoAdException) {
            if (error is T) return error
            fail("threw ${error::class.simpleName}, expected ${T::class.simpleName}")
        }
        fail("threw nothing, expected ${T::class.simpleName}")
        error("unreachable")
    }

    private fun ad(json: String, owner: VideoAds = owner()) = loaded(AdPayload.parse(Fixtures.json(json)), owner)

    private fun withTracking(json: String, tracking: String) = json.replace("\"tracking\": []", "\"tracking\": $tracking")

    private fun video(json: String) = json.replace("\"creative\": \"image\"", "\"creative\": \"video\"")

    /** Tracking entries whose URLs carry the content playhead. */
    private fun withPlayhead(vararg events: String) =
        events.joinToString(prefix = "[", postfix = "]") { """{"event": "$it", "url": "https://t.test/b?e=$it&cph=[CONTENTPLAYHEAD]"}""" }

    private fun hosts(container: ViewGroup) = (0 until container.childCount).count { container.getChildAt(it) is AdHostView }

    private fun host(container: ViewGroup) = (0 until container.childCount).map(container::getChildAt).filterIsInstance<AdHostView>().single()

    /**
     * Where the host puts the creative at the container's size, in its pixels; `null` when it no longer fits.
     * Asked of the host's own placement: Robolectric's WebView keeps no bounds from a layout pass.
     */
    private fun adFrame(container: ViewGroup): Rect? = host(container).place!!(container.width, container.height, AdHostView.systemInsets(container))

    private fun resize(container: ViewGroup, width: Int, height: Int) {
        container.layout(0, 0, width, height)
    }

    /**
     * A container in Robolectric's window (320×470 px, the content 56 px down) whose window reports system
     * bars: Robolectric's own report none. The host shares the container's window, so its placement is asked
     * with the container's insets.
     */
    private class BarsContainer(context: android.content.Context, private val bars: android.graphics.Insets) : FrameLayout(context) {
        override fun getRootWindowInsets(): WindowInsets = WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(), bars).build()
    }

    private fun barsContainer(width: Int, height: Int, left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0): BarsContainer {
        val root = container(320, 414)
        return BarsContainer(root.context, android.graphics.Insets.of(left, top, right, bottom)).also {
            root.addView(it)
            it.layout(0, 0, width, height)
        }
    }

    private fun opened(): Intent? = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity

    /** Shows [ad] from [host] and brings its screen to the front, attached to its window. */
    private fun present(ad: VideoAd, host: Activity = activity()): ActivityController<LinearAdActivity> {
        ad.show(host)
        return Robolectric.buildActivity(LinearAdActivity::class.java, shadowOf(host).nextStartedActivity).create().start().resume().visible()
    }

    private fun query(beacon: String, name: String) = Regex("[?&]$name=([^&]*)").find(beacon)?.groupValues?.get(1)

    /** A player whose position read is the app's code, counted. */
    private class Player(var playing: Boolean = true) : VideoAdsPlayer {
        var reads = 0
        var onRead: (() -> Unit)? = null
        var position: () -> Long = { 0 }
        override val currentPositionMillis: Long get() {
            reads += 1
            onRead?.invoke()
            return position()
        }
        override val isPlaying get() = playing
    }

    /** A container that runs the app's code when the SDK adds its host, as an overridden hook can. */
    private class HookContainer(context: android.content.Context) : FrameLayout(context) {
        var onAdd: (() -> Unit)? = null

        override fun onViewAdded(child: View) {
            super.onViewAdded(child)
            val action = onAdd
            onAdd = null
            action?.invoke()
        }
    }

    private fun hookContainer(): HookContainer {
        val root = container()
        return HookContainer(root.context).also {
            root.addView(it)
            it.layout(0, 0, 320, 400)
        }
    }

    // region What the ad exposes

    @Test fun aPauseAdExposesItsFactsAndHasNoDuration() {
        val ad = pause()
        assertEquals("ad-2", ad.id)
        assertEquals(AdFormat.PAUSE, ad.format)
        assertEquals(VideoAd.Price(5.5, "EUR", VideoAd.Price.Precision.ESTIMATED), ad.price)
        assertNull(ad.durationMillis)
        assertTrue(ad.isReady)
        assertFalse(ad.wasShown || ad.hasEnded || ad.isCompleted)
        assertNull(ad.invalidationReason)
    }

    @Test fun expiryIsFoundOutAtTheNextCallNotByATimer() {
        var now = 0L
        Environment.now = { now }
        val recorder = Recorder()
        val ad = pause()
        ad.listener = recorder
        now = 600_000
        idle()
        assertFalse(ad.isReady)
        assertNull("nothing happens until the next call", ad.invalidationReason)
        assertTrue(recorder.heard.isEmpty())
        assertEquals(VideoAd.InvalidationReason.Expired, assertThrows<VideoAdException.Invalidated> { ad.show(container()) }.reason)
        assertEquals(VideoAd.InvalidationReason.Expired, ad.invalidationReason)
    }

    // endregion

    // region show

    @Test fun aPauseCardsHostCoversTheContainerAndHoldsTheCreativeAtTheTopCentred() {
        val ad = ad(withTracking(Fixtures.pause(), tracking("creativeView", "iconView", "impression", "overlayViewDuration")))
        val sent = mutableListOf<String>()
        ad.loadedBridge!!.onSend = { type, _ -> sent += type }
        val container = container(320, 400)
        ad.show(container)
        laidOut()
        assertFalse("shown at most once", ad.isReady)
        val host = host(container)
        assertEquals(1, container.childCount)
        assertSame(ad.loadedBridge!!.webView, host.content)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, host.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, host.layoutParams.height)
        assertEquals("300×250 dp at density 1, centred at the top", Rect(10, 0, 310, 250), adFrame(container))
        assertEquals("ad.show", sent.first())
        assertEquals("no view duration at show", listOf("creativeView", "iconView", "impression"), events)
    }

    @Test fun anHtmlCreativeCountsItsImpressionAtShowLikeAnImage() {
        val recorder = Recorder()
        val ad = ad(Fixtures.overlay(tracking = tracking("creativeView", "impression")).replace("\"creative\": \"image\"", "\"creative\": \"html\""))
        ad.listener = recorder
        ad.show(container())
        laidOut()
        assertEquals(listOf("impression"), recorder.heard)
        assertEquals(listOf("creativeView", "impression"), events)
    }

    @Test fun aFullScreenPauseAdFillsTheContainerWhateverItsSizeAndInsets() {
        val ad = pause(position = "fullscreen")
        val container = container(320, 400)
        ad.show(container, Insets.of(10, 40, 10, 80))
        assertEquals("the insets are ignored", Rect(0, 0, 320, 400), adFrame(container))
        resize(container, 200, 150)
        assertEquals("it follows a resize", Rect(0, 0, 200, 150), adFrame(container))
        ad.dismiss()
    }

    @Test fun aContainerTooSmallLeavesNothingBehindAndABiggerOneShowsTheAd() {
        val ad = overlay(tracking = tracking("creativeView", "impression"))
        val small = container(100, 400)
        assertThrows<VideoAdException.ContainerTooSmall> { ad.show(small) }
        assertTrue(ad.isReady)
        assertFalse(ad.wasShown)
        assertNull(ad.invalidationReason)
        assertNull(Sdk.adOnScreen)
        assertEquals(0, small.childCount)
        assertTrue("nothing fired by the attempt", events.isEmpty())
        ad.show(container(320, 400))
        assertTrue(ad.wasShown)
    }

    @Test fun aSecondShowWhileTheAdIsOnScreenIsAlreadyShown() {
        val ad = overlay()
        val container = container()
        ad.show(container)
        assertThrows<VideoAdException.AlreadyShown> { ad.show(container) }
        assertEquals(1, hosts(container))
    }

    @Test fun anAdOnScreenInOneVideoBlocksAShowInAnother() {
        val first = overlay(owner(Content("ep-1", "en")))
        val second = pause(owner(Content("ep-2", "en")))
        first.show(container())
        val other = container()
        assertThrows<VideoAdException.AnotherAdShowing> { second.show(other) }
        assertTrue(second.isReady)
        assertFalse(second.wasShown)
        assertEquals(0, other.childCount)
        first.dismiss()
        second.show(other)
        assertTrue(second.wasShown)
    }

    /** A page of [root] laid out at its size and moved [dx] px sideways, as a pager places its neighbours. */
    private fun pagerPage(root: FrameLayout, dx: Float): FrameLayout {
        val page = FrameLayout(root.context)
        root.addView(page)
        page.layout(0, 0, root.width, root.height)
        page.translationX = dx
        return page
    }

    @Test fun aContainerEntirelyOutsideItsWindowIsRefusedAndTheAdStaysReady() {
        val recorder = Recorder()
        val fullscreen = pause(position = "fullscreen")
        fullscreen.listener = recorder
        val strip = overlay(tracking = tracking("impression", "creativeView"))
        strip.listener = recorder
        val root = container()
        // The next page of a pager: attached, laid out, shown, three widths to the right.
        val neighbour = pagerPage(root, 320f * 3)
        assertThrows<VideoAdException.PresenterUnavailable> { fullscreen.show(neighbour) }
        assertThrows<VideoAdException.PresenterUnavailable> { strip.show(neighbour) } // not ContainerTooSmall: the size is fine
        // Scrolled out below the window.
        val below = pagerPage(container(), 0f).apply { translationY = 2_000f }
        assertThrows<VideoAdException.PresenterUnavailable> { strip.show(below) }
        assertTrue(fullscreen.isReady && strip.isReady && !fullscreen.wasShown && !strip.wasShown)
        assertNull(Sdk.adOnScreen)
        assertTrue("nothing heard, nothing fired", recorder.heard.isEmpty() && events.isEmpty())
        // Half swiped in: on screen, shown.
        val halfway = pagerPage(container(), 160f)
        fullscreen.show(halfway)
        assertTrue(fullscreen.wasShown)
        fullscreen.dismiss()
    }

    @Test fun aContainerInAStoppedActivityOrABackgroundAppIsRefused() {
        val stopped = Robolectric.buildActivity(Activity::class.java).setup()
        val view = FrameLayout(stopped.get())
        stopped.get().setContentView(view)
        view.layout(0, 0, 320, 400)
        idle()
        view.layout(0, 0, 320, 400)
        stopped.pause().stop()
        // A window that still says visible: the Activity's own state decides.
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        val ad = pause()
        assertThrows<VideoAdException.PresenterUnavailable> { ad.show(view) }
        Environment.isAppInForeground = { false }
        assertThrows<VideoAdException.PresenterUnavailable> { ad.show(container()) }
        assertTrue(ad.isReady && !ad.wasShown)
        Environment.isAppInForeground = { true }
        ad.show(container())
        assertTrue(ad.wasShown)
    }

    @Test fun aViewTheViewerCannotSeeIsRefusedAndTheAdStaysReady() {
        val recorder = Recorder()
        val ad = pause()
        ad.listener = recorder
        val transparent = container()
        val child = FrameLayout(transparent.context)
        transparent.addView(child)
        child.layout(0, 0, 320, 400)
        transparent.alpha = 0f
        assertThrows<VideoAdException.PresenterUnavailable> { ad.show(child) }
        val hiddenWindow = container()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(hiddenWindow, "mAttachInfo"), "mWindowVisibility", View.GONE)
        assertThrows<VideoAdException.PresenterUnavailable> { ad.show(hiddenWindow) }
        assertTrue(ad.isReady && !ad.wasShown)
        val fullscreen = pause(position = "fullscreen")
        val empty = container(0, 0)
        assertThrows<VideoAdException.PresenterUnavailable> { fullscreen.show(empty) }
        assertTrue(fullscreen.isReady)
        assertTrue("no impression", recorder.heard.isEmpty())
    }

    @Test fun touchesOutsideTheCreativeReachThePlayer() {
        val ad = overlay()
        val container = container(320, 400)
        ad.show(container)
        host(container).layout(0, 0, 320, 400)
        val touch = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        assertFalse("the host takes no touch outside the creative", host(container).dispatchTouchEvent(touch))
        touch.recycle()
    }

    // endregion

    // region The system bars

    @Test fun theSystemBarsOverTheContainerKeepAStripAndACardClear() {
        // A status bar and a notch 115 px tall at the window's top reach 59 px into the container; a 34 px
        // navigation bar covers its bottom.
        val strip = overlay()
        val container = barsContainer(320, 414, top = 115, bottom = 34)
        assertEquals(Rect(0, 59, 0, 34), AdHostView.systemInsets(container))
        strip.show(container)
        assertEquals("the strip ends where the navigation bar begins", Rect(0, 300, 320, 380), adFrame(container))
        strip.dismiss()
        val card = pause()
        card.show(container)
        assertEquals("the card starts below the notch", Rect(10, 59, 310, 309), adFrame(container))
        card.dismiss()
        val fullscreen = pause(position = "fullscreen")
        fullscreen.show(container)
        // Android's WebView gives the page no safe-area insets: its page sits clear of the bars and the notch,
        // while the host still covers the whole container (the emulator test checks the laid-out views).
        assertEquals("a full-screen pause ad's page stops at the bars", Rect(0, 59, 320, 380), adFrame(container))
        fullscreen.dismiss()
    }

    @Test fun anAdFitsBetweenTheSideBarsInLandscape() {
        val ad = overlay()
        val container = barsContainer(320, 414, left = 59, right = 59, bottom = 21)
        ad.show(container)
        assertEquals("202 px between the side bars, centred, above the bottom one", Rect(59, 342, 261, 393), adFrame(container))
    }

    @Test fun theMinimumOfContainerTooSmallCountsTheSystemBarsAndAContainerOfItHoldsTheAd() {
        val ad = overlay()
        val container = barsContainer(100, 414, top = 115, bottom = 34)
        val error = assertThrows<VideoAdException.ContainerTooSmall> { ad.show(container) }
        assertEquals(160, error.minimumWidthPx)
        assertEquals("half of 80 dp, plus 59 and 34", 40 + 59 + 34, error.minimumHeightPx)
        resize(container, error.minimumWidthPx, 414)
        ad.show(container)
        assertEquals("a container of the minimum holds it", Rect(0, 340, 160, 380), adFrame(container))
    }

    @Test fun anInsetTooLargeToAddToTheSystemBarsIsTooSmall() {
        val ad = overlay()
        val container = barsContainer(320, 414, top = 115, bottom = 34)
        val error = assertThrows<VideoAdException.ContainerTooSmall> { ad.show(container, Insets.of(0, Int.MAX_VALUE, 0, 0)) }
        assertEquals("saturated, not wrapped around", Int.MAX_VALUE, error.minimumHeightPx)
        assertTrue(ad.isReady)
        assertEquals(0, container.childCount)
    }

    // endregion

    // region The app's insets

    @Test fun theAppsInsetsMoveAStripAndACardAndStayThroughAResize() {
        val strip = overlay()
        val container = container(320, 400)
        strip.show(container, Insets.of(0, 0, 0, 50))
        assertEquals("50 px above the bottom", Rect(0, 270, 320, 350), adFrame(container))
        resize(container, 300, 300)
        assertEquals("still 50 px above it, scaled to the narrower view", Rect(0, 175, 300, 250), adFrame(container))
        strip.dismiss()
        val card = pause()
        val other = container(320, 400)
        card.show(other, Insets.of(0, 20, 0, 0))
        assertEquals("20 px below the top", Rect(10, 20, 310, 270), adFrame(other))
        card.dismiss()
    }

    @Test fun theMinimumOfContainerTooSmallCountsTheAppsInsets() {
        val error = assertThrows<VideoAdException.ContainerTooSmall> { overlay().show(container(100, 400), Insets.of(5, 0, 7, 20)) }
        assertEquals("half of 320 dp, plus the side insets", 160 + 12, error.minimumWidthPx)
        assertEquals("half of 80 dp, plus the bottom inset", 40 + 20, error.minimumHeightPx)
    }

    @Test fun aCreativeThatMayNotShrinkNeedsItsOwnSize() {
        val ad = pause() // 300×250 dp, not scalable
        val error = assertThrows<VideoAdException.ContainerTooSmall> { ad.show(container(320, 200)) }
        assertEquals("its own size is the minimum", 300, error.minimumWidthPx)
        assertEquals(250, error.minimumHeightPx)
        assertTrue(ad.isReady && !ad.wasShown)
    }

    @Test fun theFitCountsTheContainersPadding() {
        // 320 px with 20 px padding each side leaves 280 px: a 300 dp creative that may not shrink does not fit.
        val ad = pause() // 300×250 dp, not scalable
        val padded = container(320, 400).apply { setPadding(20, 20, 20, 20) }
        val error = assertThrows<VideoAdException.ContainerTooSmall> { ad.show(padded) }
        assertEquals("its own size and the padding", 340, error.minimumWidthPx)
        assertEquals(290, error.minimumHeightPx)
        assertTrue("nothing spent", ad.isReady && !ad.wasShown)
        assertEquals(0, padded.childCount)
        assertTrue(events.isEmpty())
        // A container of that minimum holds it, inside the padding.
        val roomy = container(340, 400).apply { setPadding(20, 20, 20, 20) }
        ad.show(roomy)
        assertTrue(ad.wasShown)
        // The host fills the content area, so the host's own placement is asked with its size.
        assertEquals(Rect(0, 0, 300, 250), host(roomy).place!!(300, 360, Rect()))
        ad.dismiss()
        // A scalable strip shrinks to the content area and the minimum counts the padding too.
        val strip = overlay() // 320×80 dp, scalable to half
        val narrow = container(200, 400).apply { setPadding(25, 0, 25, 30) }
        val tooSmall = assertThrows<VideoAdException.ContainerTooSmall> { strip.show(narrow) }
        assertEquals(160 + 50, tooSmall.minimumWidthPx)
        assertEquals(40 + 30, tooSmall.minimumHeightPx)
    }

    @Test fun insetsThatLeaveNoWidthOrNoHeightLeaveTheAdReady() {
        for (insets in listOf(Insets.of(200, 0, 200, 0), Insets.of(0, 250, 0, 250))) {
            val ad = overlay()
            val container = container(320, 400)
            assertThrows<VideoAdException.ContainerTooSmall> { ad.show(container, insets) }
            assertTrue("$insets: the ad stays ready", ad.isReady)
            assertEquals(0, container.childCount)
        }
    }

    @Test fun hugeInsetsAreTooSmallWithAMinimumThatDoesNotOverflow() {
        val ad = overlay()
        val container = container(320, 400)
        for (insets in listOf(Insets.of(Int.MAX_VALUE, 0, 0, 0), Insets.of(0, Int.MAX_VALUE, 0, 0), Insets.of(0, 0, Int.MAX_VALUE, Int.MAX_VALUE))) {
            val error = assertThrows<VideoAdException.ContainerTooSmall> { ad.show(container, insets) }
            assertTrue("$insets: ${error.minimumWidthPx}×${error.minimumHeightPx}", error.minimumWidthPx >= 160 && error.minimumHeightPx >= 40)
        }
        assertTrue(ad.isReady)
    }

    @Test fun resizingUntilTheAppsInsetsLeaveNoRoomEndsTheAd() {
        val recorder = Recorder()
        val ad = overlay()
        ad.listener = recorder
        val container = container(320, 390)
        ad.show(container, Insets.of(60, 0, 60, 0))
        assertEquals("the 200 px between the insets", 200, adFrame(container)!!.width())
        resize(container, 200, 390)
        val host = host(container)
        host.forceLayout()
        host.layout(0, 0, 200, 390)
        idle()
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertEquals(0, container.childCount)
    }

    @Test fun aNegativeInsetCountsAsZero() {
        val ad = overlay()
        val container = container(320, 400)
        ad.show(container, Insets.of(-10, -10, -5, -20))
        assertEquals(Rect(0, 320, 320, 400), adFrame(container))
    }

    // endregion

    // region Durations and the active time

    @Test fun anOverlaysDurationFreezesWhileItsWindowIsHidden() {
        val recorder = Recorder()
        val ad = overlay(duration = "1000")
        ad.listener = recorder
        val container = container()
        ad.show(container)
        idle(300)
        val host = host(container)
        host.dispatchWindowVisibilityChanged(View.GONE)
        idle(2_000)
        assertTrue("hidden time does not count", recorder.reasons.isEmpty())
        host.dispatchWindowVisibilityChanged(View.VISIBLE)
        idle(600)
        assertTrue(recorder.reasons.isEmpty())
        idle(200)
        assertEquals("the remaining 700 ms ran once visible", listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
    }

    @Test fun anOverlaysViewDurationIsItsActiveTimeOnScreen() {
        var now = 0L
        Environment.now = { now }
        val ad = overlay(tracking = """[{"event": "overlayViewDuration", "url": "https://t.test/b?e=overlayViewDuration&ovd=[KONTEXT_VIEWDURATION]"}]""")
        val container = container()
        ad.show(container)
        now = 1_000
        host(container).dispatchWindowVisibilityChanged(View.GONE)
        now = 5_000
        host(container).dispatchWindowVisibilityChanged(View.VISIBLE)
        now = 5_250
        ad.dismiss()
        assertEquals("1250", query(beacons.single(), "ovd"))
    }

    @Test fun anOverlayWhoseDurationElapsesLeavesItsContainerAndFiresItsViewDurationNotClose() {
        val recorder = Recorder()
        val ad = overlay(duration = "1000", tracking = tracking("overlayViewDuration", "close"))
        ad.listener = recorder
        val sent = mutableListOf<String>()
        ad.loadedBridge!!.onSend = { type, _ -> if (type != "ad.exposure") sent += type }
        val container = container()
        ad.show(container)
        idle(1_100)
        assertEquals(listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
        assertTrue(ad.hasEnded && ad.isCompleted)
        assertEquals(listOf("ad.show", "ad.hide"), sent)
        assertEquals(0, container.childCount)
        assertEquals(listOf("overlayViewDuration"), events)
        ad.dismiss()
        assertEquals("exactly once", 1, recorder.reasons.size)
    }

    @Test fun aDismissBeforeTheDurationEndsTheOverlayEarlyAndStopsItsClock() {
        val recorder = Recorder()
        val ad = overlay(duration = "1000", tracking = tracking("overlayViewDuration", "close"))
        ad.listener = recorder
        ad.show(container())
        ad.dismiss()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        idle(2_000)
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        assertFalse("ended before its duration", ad.isCompleted)
        assertEquals("the view duration, not a close", listOf("overlayViewDuration"), events)
    }

    @Test fun anOverlayWithoutADurationAndAPauseAdStayUntilEnded() {
        for (ad in listOf(overlay(), pause())) {
            val recorder = Recorder()
            ad.listener = recorder
            ad.show(container())
            idle(100_000)
            assertTrue("${ad.format}: no clock ends it", recorder.reasons.isEmpty())
            ad.dismiss()
            assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        }
    }

    @Test fun dismissBeforeShowDoesNothing() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("overlayViewDuration"))
        ad.listener = recorder
        ad.dismiss()
        assertTrue(recorder.heard.isEmpty())
        assertTrue(ad.isReady)
        assertTrue(events.isEmpty())
    }

    @Test fun dismissInsideTheImpressionLeavesNothingRunning() {
        val ad = overlay(duration = "1000")
        val heard = mutableListOf<String>()
        ad.listener = object : VideoAdListener {
            override fun onAdImpression(ad: VideoAd) {
                heard += "impression"
                ad.dismiss()
            }

            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                heard += "ended $reason"
            }
        }
        ad.show(container())
        laidOut()
        assertEquals(listOf("impression", "ended DISMISSED"), heard)
        assertNull("no clock", ad.clock)
        assertNull("no meter", ad.meter)
        assertNull("no WebView", ad.loadedBridge)
        idle(2_000)
        assertEquals(2, heard.size)
    }

    @Test fun anAdOnScreenIgnoresInvalidationAndExpiry() {
        var now = 0L
        Environment.now = { now }
        val recorder = Recorder()
        val ad = overlay()
        ad.listener = recorder
        ad.show(container())
        ad.invalidate(VideoAd.InvalidationReason.Expired)
        now = 10_000_000
        assertTrue(recorder.reasons.isEmpty())
        assertNull(ad.invalidationReason)
        ad.dismiss()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
    }

    @Test fun theContainerLeavingTheWindowEndsTheAdAsInterrupted() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("overlayViewDuration", "close"))
        ad.listener = recorder
        val container = container()
        ad.show(container)
        (container.parent as ViewGroup).removeView(container)
        idle(1_000)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertNull("the next show in the app is not blocked", Sdk.adOnScreen)
        assertEquals(listOf("overlayViewDuration"), events)
    }

    @Test fun aContainerReParentedAtOnceDoesNotEndTheAd() {
        val recorder = Recorder()
        val ad = overlay()
        ad.listener = recorder
        val container = container()
        ad.show(container)
        val parent = container.parent as ViewGroup
        parent.removeView(container)
        parent.addView(container)
        idle(1_000)
        assertTrue(recorder.reasons.isEmpty())
        assertTrue(ad.wasShown && !ad.hasEnded)
    }

    // endregion

    // region The page's events

    @Test fun aShownVideoEndedBeforeItsFirstFrameSendsCloseAndNoImpression() {
        // Dismissed by the app.
        val pause = ad(video(withTracking(Fixtures.pause(position = "fullscreen"), tracking("creativeView", "impression", "close", "error"))))
        val recorder = Recorder()
        pause.listener = recorder
        pause.show(container())
        pause.dismiss()
        assertEquals(listOf("creativeView", "close"), events)
        assertEquals(listOf("ended"), recorder.heard)
        // A linear ad whose VideoAds the app destroys, and one whose screen the system takes away.
        for (end in listOf<(VideoAds, ActivityController<LinearAdActivity>) -> Unit>({ owner, screen -> owner.destroy().also { screen.pause().stop().destroy() } }, { _, screen -> screen.pause().stop().destroy() })) {
            beacons.clear()
            val owner = owner()
            val linear = linear(owner, tracking = tracking("creativeView", "impression", "close", "error"))
            val screen = present(linear)
            end(owner, screen)
            assertTrue(linear.hasEnded)
            assertEquals(listOf("creativeView", "close"), events)
        }
    }

    @Test fun aShownVideoThatCannotStartFailsWithItsErrorAndNoClose() {
        val ad = linear(tracking = tracking("creativeView", "impression", "close", "error"))
        val recorder = Recorder()
        ad.listener = recorder
        val screen = present(ad)
        page(ad, "ad.error", """"code": 405""")
        screen.pause().stop().destroy()
        assertEquals(listOf("failed", "ended"), recorder.heard)
        assertEquals(listOf("creativeView", "error"), events)
    }

    @Test fun aVideoEndedAfterItsFirstFrameSendsNoClose() {
        val ad = ad(video(withTracking(Fixtures.pause(position = "fullscreen"), tracking("impression", "close"))))
        ad.show(container())
        page(ad, "video.start", """"playhead": 0""")
        ad.dismiss()
        assertEquals(listOf("impression"), events)
    }

    @Test fun aPauseAdsVideoCompletingLeavesItsEndToTheViewer() {
        val recorder = Recorder()
        val ad = ad(video(withTracking(Fixtures.pause(position = "fullscreen"), tracking("complete", "close", "overlayViewDuration"))))
        ad.listener = recorder
        ad.show(container())
        page(ad, "video.start", """"playhead": 0""")
        page(ad, "video.complete", """"playhead": 6000""")
        assertFalse("a pause ad outlives its video", ad.hasEnded)
        page(ad, "ad.close", """"reason": "resume"""")
        assertEquals(listOf(VideoAd.EndReason.RESUMED), recorder.reasons)
        assertTrue(ad.isCompleted)
        assertEquals(listOf("complete", "close", "overlayViewDuration"), events)
    }

    @Test fun aLinearPageEndingItselfAfterTheVideoIsCompletedWithoutAClose() {
        val recorder = Recorder()
        val ad = linear(tracking = tracking("complete", "close", "skip"))
        ad.listener = recorder
        val screen = present(ad)
        page(ad, "video.start", """"playhead": 0""")
        page(ad, "video.complete", """"playhead": 30000""")
        page(ad, "ad.close", """"reason": "complete"""")
        screen.pause().stop().destroy()
        assertEquals(listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
        assertTrue(ad.isCompleted)
        assertEquals("nobody closed anything", listOf("complete"), events)
    }

    @Test fun aClickThroughCarriesTheClickBeaconsOwnFireId() {
        val ad = overlay(tracking = """[{"event": "click", "url": "https://t.test/b?e=click&fid=[KONTEXT_FIREID]"}]""")
        ad.show(container())
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", """"url": "https://brand.test/r?fid=[KONTEXT_FIREID]"""")
        val fid = query(beacons.single(), "fid")!!
        assertTrue(fid.isNotEmpty())
        assertEquals("one occurrence: the redirect and the beacon", fid, query(opened()!!.dataString!!, "fid"))
    }

    @Test fun anOpenForAnotherPurposeOpensItsUrlAndFiresNothing() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("iconClick", "click"))
        ad.listener = recorder
        ad.show(container())
        for (purpose in listOf("", """, "purpose": "whyThisAd"""")) {
            ad.loadedBridge!!.noteTouch()
            page(ad, "ad.open", """"url": "https://privacy.test"$purpose""")
            assertEquals("https://privacy.test", opened()!!.dataString)
        }
        assertTrue("not clicks", recorder.clicks.isEmpty())
        assertTrue("neither a click nor the AdChoices icon's trackers", events.isEmpty())
    }

    @Test fun aCreativeThatDiesBeforeShowTellsTheListenerNothing() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("error"))
        ad.listener = recorder
        page(ad, "ad.error", """"code": 502""")
        assertEquals(VideoAd.InvalidationReason.CreativeFailed(502), ad.invalidationReason)
        assertFalse(ad.isReady)
        assertTrue("nothing is delivered before show", recorder.heard.isEmpty())
        assertEquals(listOf("error"), events)
    }

    @Test fun aVideoImpressionWaitsForTheFirstFrameAndCountsOnce() {
        val recorder = Recorder()
        val ad = linear(tracking = tracking("creativeView", "impression", "start"))
        ad.listener = recorder
        present(ad)
        assertTrue("no impression at show for a video", recorder.heard.isEmpty())
        assertEquals(listOf("creativeView"), events)
        page(ad, "video.start", """"playhead": 0""")
        page(ad, "video.start", """"playhead": 0""")
        assertEquals(listOf("impression"), recorder.heard)
        assertEquals(listOf("creativeView", "start", "impression"), events)
    }

    @Test fun aQuartileFiresTheCheckpointsItsPlayheadPassed() {
        val ad = linear(tracking = """[{"event": "progress", "url": "https://t.test/b?e=p5", "offset": 5000}]""")
        present(ad)
        page(ad, "video.start", """"playhead": 0""")
        page(ad, "video.midpoint", """"playhead": 7000""")
        assertEquals("decided from the playhead of any video message", listOf("p5"), events)
        page(ad, "video.progress", """"offset": 5000, "playhead": 7100""")
        assertEquals("a late report of the same checkpoint fires nothing", listOf("p5"), events)
    }

    @Test fun everyVideoMomentFiresItsBeaconWithTheAdPlayhead() {
        val moments = listOf("start", "firstQuartile", "pause", "resume", "mute", "unmute", "midpoint", "thirdQuartile", "skip")
        val tracking = (moments + "overlayViewDuration").joinToString(prefix = "[", postfix = "]") {
            """{"event": "$it", "url": "https://t.test/b?e=$it&aph=[ADPLAYHEAD]"}"""
        }
        val recorder = Recorder()
        val ad = linear(tracking = tracking)
        ad.listener = recorder
        val screen = present(ad)
        page(ad, "video.start", """"playhead": 0""")
        page(ad, "video.firstQuartile", """"playhead": 2500""")
        page(ad, "video.pause", """"playhead": 3000""")
        page(ad, "video.resume", """"playhead": 3000""")
        page(ad, "video.mute", """"playhead": 3100""")
        page(ad, "video.unmute", """"playhead": 3200""")
        page(ad, "video.midpoint", """"playhead": 5000""")
        page(ad, "video.thirdQuartile", """"playhead": 7500""")
        page(ad, "video.skip", """"playhead": 8000""")
        screen.pause().stop().destroy()
        assertEquals("linear ads have no view duration", moments, events)
        assertEquals("00%3A00%3A03.000", query(beacons.single { "e=pause" in it }, "aph"))
        assertEquals("00%3A00%3A08.000", query(beacons.single { "e=skip" in it }, "aph"))
        assertEquals(listOf(VideoAd.EndReason.SKIPPED), recorder.reasons)
    }

    @Test fun anEndCardClickOnAnAdWithoutEndCardEntriesFiresTheCreativesClick() {
        val ad = overlay(tracking = tracking("click"))
        ad.show(container())
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", """"url": "https://brand.test", "area": "endCard"""")
        assertEquals("every accepted click is counted", listOf("https://t.test/b?e=click"), beacons)
    }

    @Test fun anEndCardClickFiresTheEndCardsEntryOnly() {
        val ad = linear(tracking = tracking("click", "click|endCard"))
        present(ad)
        page(ad, "video.start", """"playhead": 0""")
        page(ad, "video.complete", """"playhead": 30000""")
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", """"url": "https://brand.test", "area": "endCard"""")
        assertEquals("the creative's own click entry stays unfired", listOf("https://t.test/b?e=click&x=endCard"), beacons)
    }

    @Test fun aVideoCountsTowardsViewabilityOnlyWhilePlaying() {
        val ad = ad(video(Fixtures.overlay(tracking = "[]")))
        ad.show(container())
        assertFalse("exposed, but no video played yet", ad.meter!!.countsTime)
        page(ad, "video.start", """"playhead": 0""")
        assertTrue(ad.meter!!.countsTime)
        page(ad, "video.pause", """"playhead": 500""")
        assertFalse("paused", ad.meter!!.countsTime)
        page(ad, "video.resume", """"playhead": 500""")
        assertTrue(ad.meter!!.countsTime)
        page(ad, "video.complete", """"playhead": 1500""")
        assertFalse("an end card on screen is not the video playing", ad.meter!!.countsTime)
    }

    @Test fun aLinearVideoThatStartedBeforeItsScreenCameUpCountsOnceItIsUp() {
        val recorder = Recorder()
        val ad = linear()
        ad.listener = recorder
        val host = activity()
        ad.show(host)
        assertNull("the page and the meter wait for the screen", ad.meter)
        page(ad, "video.start", """"playhead": 0""")
        assertTrue("nothing from the page before the screen is up", recorder.heard.isEmpty())
        Robolectric.buildActivity(LinearAdActivity::class.java, shadowOf(host).nextStartedActivity).create().start().resume().visible()
        page(ad, "video.start", """"playhead": 0""")
        assertTrue(ad.meter!!.countsTime)
        assertEquals(listOf("impression"), recorder.heard)
    }

    @Test fun aDestroyInsideTheVideoImpressionEndsTheAdAndNothingFiresAfter() {
        val owner = owner()
        val ad = ad(video(Fixtures.overlay(tracking = tracking("start", "impression", "firstQuartile"))), owner)
        val heard = mutableListOf<String>()
        ad.listener = object : VideoAdListener {
            override fun onAdImpression(ad: VideoAd) {
                heard += "impression"
                owner.destroy()
            }

            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                heard += "ended $reason"
            }
        }
        ad.show(container())
        page(ad, "video.start", """"playhead": 0""")
        ad.handle(FrameEvent.Video(FrameEvent.VideoEvent.FIRST_QUARTILE, 2_500))
        assertEquals(listOf("impression", "ended DESTROYED"), heard)
        assertEquals("the start's own beacons, nothing after the end", listOf("start", "impression"), events)
    }

    @Test fun aDestroyInsideOnAdFailedKeepsTheFailedReason() {
        val owner = owner()
        val ad = overlay(owner)
        val heard = mutableListOf<String>()
        ad.listener = object : VideoAdListener {
            override fun onAdImpression(ad: VideoAd) {
                heard += "impression"
            }

            override fun onAdFailed(ad: VideoAd, error: VideoAdException) {
                heard += "failed"
                owner.destroy()
            }

            override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                heard += "ended $reason"
            }
        }
        ad.show(container())
        laidOut()
        page(ad, "ad.error", """"code": 405""")
        assertEquals(listOf("impression", "failed", "ended FAILED"), heard)
        assertTrue(owner.isDestroyed)
    }

    // endregion

    // region The linear screen

    @Test fun theLinearScreenHoldsThePageFullScreenOnBlackAndTheAdClosesIt() {
        val recorder = Recorder()
        val ad = linear()
        ad.listener = recorder
        val host = activity()
        val screen = present(ad, host)
        assertEquals("a fade in", android.R.anim.fade_in, shadowOf(host).pendingTransitionEnterAnimationResourceId)
        val root = screen.get().findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as FrameLayout
        assertEquals(Color.BLACK, (root.background as ColorDrawable).color)
        val webView = ad.loadedBridge!!.webView
        assertSame(webView, root.getChildAt(0))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.height)
        page(ad, "video.start", """"playhead": 0""")
        page(ad, "video.skip", """"playhead": 1000""")
        assertTrue("the ad finishes its screen", screen.get().isFinishing && screen.get().isEndingByAd)
        assertTrue(recorder.reasons.isEmpty())
        screen.pause().stop().destroy()
        assertEquals(listOf(VideoAd.EndReason.SKIPPED), recorder.reasons)
    }

    /** A player screen the test drives, and a container in its window, as `container()` makes one. */
    private fun playerScreen(): Pair<ActivityController<Activity>, FrameLayout> {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val view = FrameLayout(controller.get())
        controller.get().setContentView(view)
        view.layout(0, 0, 320, 400)
        idle()
        view.layout(0, 0, 320, 400)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        return controller to view
    }

    @Test fun anotherScreenOfTheAppOverAShownPauseAdEndsItInterrupted() {
        for (ad in listOf(pause(), overlay())) {
            val recorder = Recorder()
            ad.listener = recorder
            val (player, container) = playerScreen()
            ad.show(container)
            // The app starts a screen of its own over the player: it starts before the player stops.
            val cover = Robolectric.buildActivity(Activity::class.java).setup()
            player.pause().stop()
            assertEquals("as on iOS, where a full-screen cover takes the view out of its window", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
            assertEquals(0, hosts(container))
            cover.pause().stop().destroy()
            player.destroy()
        }
    }

    @Test fun theAppGoingToTheBackgroundKeepsAShownPauseAd() {
        val recorder = Recorder()
        val ad = pause()
        ad.listener = recorder
        val (player, container) = playerScreen()
        ad.show(container)
        player.pause().stop() // Home: nothing of the app is started any more
        idle(1_000)
        assertFalse("the background only freezes it", ad.hasEnded)
        player.restart().start().resume()
        assertFalse(ad.hasEnded)
        ad.dismiss()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
    }

    @Test fun theActivityThatStartedALinearAdFinishingUnderItEndsItInterruptedAndClosesItsScreen() {
        val recorder = Recorder()
        val ad = linear()
        ad.listener = recorder
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val screen = present(ad, host.get())
        page(ad, "video.start", """"playhead": 0""")
        host.pause().stop()
        host.get().finish()
        host.destroy()
        assertTrue("its screen closes", screen.get().isFinishing && screen.get().isEndingByAd)
        screen.pause().stop().destroy()
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun theActivityThatStartedALinearAdDestroyedWithoutFinishingKeepsIt() {
        val recorder = Recorder()
        val ad = linear()
        ad.listener = recorder
        val host = Robolectric.buildActivity(Activity::class.java).setup()
        val screen = present(ad, host.get())
        host.pause().stop()
        host.destroy() // a recreation, or the system reclaiming a screen behind: the app comes back to it
        assertFalse(ad.hasEnded)
        page(ad, "video.skip", """"playhead": 6000""")
        screen.pause().stop().destroy()
        assertEquals(listOf(VideoAd.EndReason.SKIPPED), recorder.reasons)
    }

    @Test fun aLinearScreenCoveredByAnotherWaitsAndEndsOnceLater() {
        val recorder = Recorder()
        val ad = linear()
        ad.listener = recorder
        val screen = present(ad)
        page(ad, "video.start", """"playhead": 0""")
        screen.pause().stop() // another Activity came over it
        idle(2_000)
        assertTrue("no end while covered", recorder.reasons.isEmpty())
        assertFalse(ad.hasEnded)
        screen.restart().start().resume()
        page(ad, "video.skip", """"playhead": 3000""")
        screen.pause().stop().destroy()
        idle(1_000)
        assertEquals("one terminal callback", listOf(VideoAd.EndReason.SKIPPED), recorder.reasons)
    }

    @Test fun aCoveredLinearScreenThatTheSystemThenDestroysIsInterruptedOnce() {
        val recorder = Recorder()
        val ad = linear(tracking = tracking("close", "skip"))
        ad.listener = recorder
        val screen = present(ad)
        screen.pause().stop()
        idle(1_000)
        assertTrue(recorder.reasons.isEmpty())
        screen.destroy()
        idle(1_000)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertEquals("shown, not billed: its video never started", listOf("close"), events)
    }

    @Test fun aLinearPageTakenOffItsScreenIsInterrupted() {
        val recorder = Recorder()
        val ad = linear()
        ad.listener = recorder
        val screen = present(ad)
        val webView = ad.loadedBridge!!.webView
        (webView.parent as ViewGroup).removeView(webView)
        idle(1_000) // the meter's detachment checks
        assertTrue("the ad ended", ad.hasEnded)
        assertTrue("its empty screen, where Back does nothing, is closed", screen.get().isFinishing)
        assertTrue("onAdEnded waits for the screen to be gone", recorder.reasons.isEmpty())
        screen.pause().stop().destroy()
        idle(3_000)
        assertEquals("once, after the screen is gone", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertNull(Sdk.adOnScreen)
    }

    @Test fun aLinearScreenThatNeverStartsEndsTheAdAsFailedWithNothingBillable() {
        VideoAd.presentationTimeoutMillis = 500
        val recorder = Recorder()
        val ad = linear(tracking = tracking("creativeView", "impression", "start"))
        ad.listener = recorder
        ad.show(activity())
        assertSame(ad, Sdk.adOnScreen)
        page(ad, "video.start", """"playhead": 0""") // a page that started anyway: dropped, it was never shown
        idle(700)
        assertEquals("no impression for an ad nobody saw", listOf("failed", "ended"), recorder.heard)
        assertTrue(recorder.errors.single() is VideoAdException.PresenterUnavailable)
        assertTrue("nothing billable", events.isEmpty())
        assertNull("the screen is free again", Sdk.adOnScreen)
    }

    @Test fun aDestroyWhileTheLinearScreenIsUpClosesItAndEndsAsDestroyed() {
        val recorder = Recorder()
        val owner = owner()
        val ad = linear(owner, tracking = tracking("close", "skip", "overlayViewDuration"))
        ad.listener = recorder
        val screen = present(ad)
        page(ad, "video.start", """"playhead": 0""")
        owner.destroy()
        assertTrue("the SDK closes its screen", screen.get().isFinishing && screen.get().isEndingByAd)
        screen.pause().stop().destroy()
        assertEquals("the ad's own close is not an interruption", listOf(VideoAd.EndReason.DESTROYED), recorder.reasons)
        assertTrue("no VAST event for an app abort", events.isEmpty())
    }

    // endregion

    // region The app's code inside the SDK's

    @Test fun aPlayheadReadThatDestroysAtShowMeansNothingIsShown() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = ad(withTracking(Fixtures.pause(), withPlayhead("creativeView", "impression")), owner)
        ad.listener = recorder
        val container = container()
        player.reads = 0
        player.onRead = { owner.destroy() }
        assertThrows<VideoAdException.Destroyed> { ad.show(container) }
        assertEquals("read once, before the show changed anything", 1, player.reads)
        assertFalse(ad.wasShown)
        assertTrue(recorder.heard.isEmpty())
        assertNull(Sdk.adOnScreen)
        assertEquals(0, container.childCount)
        assertTrue(events.isEmpty())
    }

    @Test fun aShowReadsThePlayheadOnceForEveryBeaconOfIt() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val ad = ad(withTracking(Fixtures.pause(), withPlayhead("creativeView", "iconView", "impression")), owner)
        player.reads = 0
        player.position = { player.reads * 1_000L } // advances on every read
        ad.show(container())
        assertEquals(1, player.reads)
        assertEquals(listOf("creativeView", "iconView"), events)
        assertEquals("one moment", setOf("00%3A00%3A01.000"), beacons.map { query(it, "cph") }.toSet())
        // The impression is its own moment, at the first layout: one read more.
        laidOut()
        assertEquals(2, player.reads)
        assertEquals(listOf("creativeView", "iconView", "impression"), events)
        assertEquals("00%3A00%3A02.000", query(beacons.last(), "cph"))
    }

    @Test fun aPlayheadReadThatEndsTheAdOnEveryReadIsReadOnce() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = ad(Fixtures.overlay(duration = "null", tracking = withPlayhead("overlayViewDuration")), owner)
        ad.listener = recorder
        ad.show(container())
        laidOut()
        player.reads = 0
        // Bounded only so that a regression fails instead of overflowing the stack.
        player.onRead = { if (player.reads < 20) ad.dismiss() }
        ad.dismiss()
        assertEquals("the read's own dismiss reads nothing more", 1, player.reads)
        assertEquals("ended once", listOf("impression", "ended"), recorder.heard)
    }

    @Test fun aClickWhoseUrlsAloneAskForThePlayheadReadsItOnce() {
        val attempted = mutableListOf<String>()
        val app = Environment.context!!
        Environment.context = object : ContextWrapper(app) {
            override fun getApplicationContext() = this

            override fun startActivity(intent: Intent) {
                attempted += intent.dataString!!
                if (intent.data?.scheme == "brandapp") throw ActivityNotFoundException()
            }
        }
        try {
            val player = Player()
            val owner = owner()
            owner.track(player)
            val ad = overlay(owner, tracking = tracking("click"))
            ad.show(container())
            player.reads = 0
            player.position = { player.reads * 1_000L }
            ad.handle(FrameEvent.Click("brandapp://a?cph=[CONTENTPLAYHEAD]", "https://brand.test/b?cph=[CONTENTPLAYHEAD]", VideoAd.Click.Area.Creative))
            assertEquals(2, attempted.size)
            assertEquals("one read for the click and its fallback", 1, player.reads)
            assertEquals(listOf("00%3A00%3A01.000", "00%3A00%3A01.000"), attempted.map { query(it, "cph") })
        } finally {
            Environment.context = app
        }
    }

    @Test fun aShowFromTheContainersAddHookFindsTheSlotTaken() {
        val firstRecorder = Recorder()
        val secondRecorder = Recorder()
        val first = overlay()
        val second = pause()
        first.listener = firstRecorder
        second.listener = secondRecorder
        val container = hookContainer()
        val other = container()
        var nested: VideoAdException? = null
        container.onAdd = {
            try {
                second.show(other)
            } catch (error: VideoAdException) {
                nested = error
            }
        }
        first.show(container)
        laidOut()
        assertTrue("$nested", nested is VideoAdException.AnotherAdShowing)
        assertTrue(first.wasShown && !second.wasShown && second.isReady)
        assertEquals(listOf("impression"), firstRecorder.heard)
        assertTrue(secondRecorder.heard.isEmpty())
        assertEquals("the refused show left nothing behind", 0, other.childCount)
        assertSame(first, Sdk.adOnScreen)
    }

    @Test fun aDestroyFromTheContainersAddHookEndsTheAdWithNothingLeft() {
        val owner = owner()
        val recorder = Recorder()
        val ad = overlay(owner, tracking = tracking("creativeView", "impression", "overlayViewDuration", "close"))
        ad.listener = recorder
        val container = hookContainer()
        container.onAdd = { owner.destroy() }
        ad.show(container)
        assertTrue(ad.wasShown && ad.hasEnded)
        assertEquals("no impression for an ad that ended before it was on screen", listOf("ended"), recorder.heard)
        assertEquals(listOf(VideoAd.EndReason.DESTROYED), recorder.reasons)
        assertEquals(0, container.childCount)
        assertNull(Sdk.adOnScreen)
        assertTrue("no view duration for an ad never on screen: $events", events.isEmpty())
    }

    @Test fun aHookThatTakesTheContainerOffScreenEndsTheAdWithoutAnImpression() {
        val recorder = Recorder()
        val ad = overlay(tracking = tracking("creativeView", "impression", "overlayViewDuration", "close"))
        ad.listener = recorder
        val container = hookContainer()
        container.onAdd = { (container.parent as ViewGroup).removeView(container) }
        ad.show(container)
        assertTrue(ad.wasShown && ad.hasEnded)
        assertEquals("committed, then ended at once, never seen", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertEquals(listOf("ended"), recorder.heard)
        assertEquals(0, container.childCount)
        assertNull(Sdk.adOnScreen)
        assertTrue("no view duration for an ad never on screen: $events", events.isEmpty())
    }

    private enum class Hostile { DESTROY_OWNER, SHOW_THIS_AD, SHOW_ANOTHER_AD, DISMISS_THIS_AD }

    private enum class Operation(val needsShown: Boolean) {
        SHOW(false), REJECT(false), DISMISS(true), ERROR_BEFORE_SHOW(false), ERROR_AFTER_SHOW(true), CLICK(true), VIDEO_START(true), DESTROY(true)
    }

    /**
     * Every operation, with a player whose position read does something hostile the first time the SDK reads
     * it. Whatever it does: at most one ad on screen, `onAdEnded` last and once, no impression for an ad not
     * shown, an invalidated ad never shown, and no host left behind.
     */
    @Test fun aHostilePlayheadReadThroughEveryOperationKeepsTheGuarantees() {
        for (hostile in Hostile.values()) {
            for (operation in Operation.values()) hostileRound(hostile, operation)
        }
    }

    /** One ad, with the player its owner tracks, and another ad of another video, each with its own container. */
    private inner class Round(label: String) {
        val player = Player()
        val owner = owner().also { it.track(player) }
        val otherOwner = owner()
        val ad = ad(video(Fixtures.overlay(id = "x-$label", duration = "null", tracking = HOSTILE_TRACKING)), owner)
        val another = ad(Fixtures.pause(id = "y-$label"), otherOwner)
        val subjects = listOf(Triple(ad, Recorder(), container()), Triple(another, Recorder(), container()))

        init {
            subjects.forEach { (subject, recorder, _) -> subject.listener = recorder }
        }
    }

    private fun hostileRound(hostile: Hostile, operation: Operation) {
        val label = "$hostile during $operation"
        val round = Round("$hostile-$operation")
        val (ad, _, main) = round.subjects[0]
        if (operation.needsShown) {
            ad.show(main)
            if (operation != Operation.VIDEO_START) page(ad, "video.start", """"playhead": 0""")
        }
        var armed = true
        round.player.onRead = {
            if (armed) {
                armed = false
                ignoringRefusals { act(hostile, round) }
            }
        }
        ignoringRefusals { operate(operation, round) }
        checkGuarantees(label, round)
        round.player.onRead = null
        for ((shown, _, _) in round.subjects) if (shown.wasShown && !shown.hasEnded) shown.dismiss()
        round.owner.destroy()
        round.otherOwner.destroy()
        assertNull("$label: the slot is free afterwards", Sdk.adOnScreen)
    }

    private fun ignoringRefusals(block: () -> Unit) {
        try {
            block()
        } catch (_: VideoAdException) {
        }
    }

    private fun act(hostile: Hostile, round: Round) = when (hostile) {
        Hostile.DESTROY_OWNER -> round.owner.destroy()
        Hostile.SHOW_THIS_AD -> round.ad.show(round.subjects[0].third)
        Hostile.SHOW_ANOTHER_AD -> round.another.show(round.subjects[1].third)
        Hostile.DISMISS_THIS_AD -> round.ad.dismiss()
    }

    private fun operate(operation: Operation, round: Round) = when (operation) {
        Operation.SHOW -> round.ad.show(round.subjects[0].third)
        Operation.REJECT -> round.ad.reject()
        Operation.DISMISS -> round.ad.dismiss()
        Operation.ERROR_BEFORE_SHOW, Operation.ERROR_AFTER_SHOW -> round.ad.handle(FrameEvent.Error(405))
        Operation.CLICK -> round.ad.handle(FrameEvent.Click("https://brand.test/x", null, VideoAd.Click.Area.Creative))
        Operation.VIDEO_START -> round.ad.handle(FrameEvent.Video(FrameEvent.VideoEvent.START, 0))
        Operation.DESTROY -> round.owner.destroy()
    }

    private fun checkGuarantees(label: String, round: Round) {
        val onScreen = round.subjects.map { it.first }.filter { it.wasShown && !it.hasEnded }
        assertTrue("$label: at most one ad on screen", onScreen.size <= 1)
        Sdk.adOnScreen?.let { slot -> assertTrue("$label: the slot holds the ad on screen", onScreen.any { it === slot }) }
        for ((subject, recorder, view) in round.subjects) {
            val heard = recorder.heard
            val ends = heard.count { it == "ended" }
            assertEquals("$label: ${subject.id} ends once if shown: $heard", if (subject.wasShown && subject.hasEnded) 1 else 0, ends)
            if (ends > 0) assertEquals("$label: onAdEnded is last: $heard", "ended", heard.last())
            if ("impression" in heard) assertTrue("$label: an impression only for a shown ad", subject.wasShown)
            if (subject.invalidationReason != null) assertFalse("$label: an invalidated ad is never shown", subject.wasShown)
            assertEquals("$label: ${subject.id} leaves no host behind", if (subject.wasShown && !subject.hasEnded) 1 else 0, hosts(view))
        }
    }

    // endregion

    private companion object {
        const val HOSTILE_TRACKING = """[""" +
            """{"event": "creativeView", "url": "https://t.test/b?e=creativeView&cph=[CONTENTPLAYHEAD]"}, """ +
            """{"event": "impression", "url": "https://t.test/b?e=impression&cph=[CONTENTPLAYHEAD]"}, """ +
            """{"event": "start", "url": "https://t.test/b?e=start&cph=[CONTENTPLAYHEAD]"}, """ +
            """{"event": "click", "url": "https://t.test/b?e=click&cph=[CONTENTPLAYHEAD]"}, """ +
            """{"event": "error", "url": "https://t.test/b?e=error&cph=[CONTENTPLAYHEAD]"}, """ +
            """{"event": "notUsed", "url": "https://t.test/b?e=notUsed&cph=[CONTENTPLAYHEAD]"}, """ +
            """{"event": "overlayViewDuration", "url": "https://t.test/b?e=overlayViewDuration&cph=[CONTENTPLAYHEAD]"}]"""
    }
}
