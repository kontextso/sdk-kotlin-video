package so.kontext.video

import android.view.View
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot

/**
 * The meter is the one answer to "is it seen": an overlay or pause ad scrolled or paged out of view while its
 * window stays up (a feed cell swiped away) stops counting its time on screen, and after a second unseen in front
 * it ends, interrupted, freeing the app's one slot. In the background it only freezes.
 */
@RunWith(RobolectricTestRunner::class)
class OffScreenTest : SdkTest() {
    private var now = 0L

    @After fun restore() {
        ViewabilityMeter.intervalMillis = 250
    }

    /** A pause ad shown and drawn in a container; the test ticks the meter itself. */
    private fun shown(): Pair<VideoAd, View> {
        ViewabilityMeter.intervalMillis = 1_000_000
        Environment.now = { now }
        Environment.isAppInForeground = { true }
        val ad = pause()
        val container = container()
        ad.show(container)
        laidOut()
        // Robolectric measures a WebView at no size: a real one is drawn where the host placed it.
        val webView = ad.loadedBridge!!.webView
        ReflectionHelpers.setField(webView, "mRight", 300)
        ReflectionHelpers.setField(webView, "mBottom", 200)
        ad.meter!!.tick()
        assertTrue("seen at first", ad.meter!!.isSeen)
        return ad to container
    }

    private fun ticks(ad: VideoAd, millis: Long) {
        repeat((millis / 100).toInt()) {
            now += 100
            ad.meter?.tick()
        }
    }

    @Test fun anAdScrolledOutOfViewStopsCountingAndEndsAfterASecond() {
        val (ad, container) = shown()
        val recorder = Recorder().also { ad.listener = it }
        assertTrue(ad.onScreenClock!!.isCounting)
        container.translationX = 10_000f // a feed cell swiped away, still in its window
        ticks(ad, 500)
        assertFalse("unseen: not on screen time", ad.onScreenClock!!.isCounting)
        assertFalse("half a second is a peek, not gone", ad.hasEnded)
        container.translationX = 0f
        ticks(ad, 100)
        assertTrue("back in view: counting again", ad.onScreenClock!!.isCounting)
        container.translationX = 10_000f
        ticks(ad, 1_100)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertNull("the slot is free for the next episode's ad", ScreenSlot.current)
    }

    @Test fun anAdWhollyOffScreenWhereItIsPlacedIsNeverCounted() {
        ViewabilityMeter.intervalMillis = 1_000_000
        Environment.now = { now }
        Environment.isAppInForeground = { true }
        val ad = pause()
        val recorder = Recorder().also { ad.listener = it }
        val container = container()
        ad.show(container)
        // The player shows on screen, the ad's own box lies above what shows of it (a feed scrolled to its middle).
        val webView = ad.loadedBridge!!.webView
        ReflectionHelpers.setField(webView, "mRight", 300)
        ReflectionHelpers.setField(webView, "mBottom", 200)
        webView.translationY = -10_000f
        laidOut()
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        assertFalse("no impression for an ad nobody could see", recorder.heard.contains("impression"))
        assertNull(ScreenSlot.current)
    }

    @Test fun inTheBackgroundAnUnseenAdOnlyFreezes() {
        val (ad, container) = shown()
        Environment.isAppInForeground = { false }
        container.translationX = 10_000f
        ticks(ad, 3_000)
        assertFalse(ad.hasEnded)
        assertFalse(ad.onScreenClock!!.isCounting)
        // Back in front and in view: it goes on.
        Environment.isAppInForeground = { true }
        container.translationX = 0f
        ticks(ad, 100)
        assertFalse(ad.hasEnded)
        assertTrue(ad.onScreenClock!!.isCounting)
    }
}
