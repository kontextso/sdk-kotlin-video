package so.kontext.video.internal.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.SdkTest
import so.kontext.video.VideoAd

/** An inline ad's screen: the variant it takes for its container, and how its host follows the container's size. */
@RunWith(RobolectricTestRunner::class)
class InlineScreenTest : SdkTest() {
    @Test fun theHostRefitsTheAdWhenTheContainerResizesAndEndsItWhenTooSmall() {
        val recorder = Recorder()
        val ad = overlay()
        ad.listener = recorder
        // Inside Robolectric's window, whose system insets the host keeps the ad clear of.
        val container = container(width = 320, height = 400)
        ad.show(container)
        val host = container.getChildAt(0) as InlineAdView
        // What the host lays the ad out at, for the container's size and insets (Robolectric's WebView keeps no bounds).
        val place = host.place!!
        assertEquals(android.graphics.Rect(0, 320, 320, 400), place(320, 400, android.graphics.Rect()))
        assertEquals("follows a shorter container", android.graphics.Rect(0, 220, 320, 300), place(320, 300, android.graphics.Rect()))
        assertEquals("kept clear of the insets", android.graphics.Rect(0, 200, 320, 280), place(320, 300, android.graphics.Rect(0, 0, 0, 20)))
        assertNull("no room", place(100, 300, android.graphics.Rect()))
        container.layout(0, 0, 100, 300) // too narrow for any variant at half size
        host.forceLayout()
        host.layout(0, 0, 100, 300)
        idle()
        assertTrue("one pass is not enough", recorder.reasons.isEmpty())
        idle(InlineAdView.unfitGraceMillis)
        assertEquals("still too small after the grace", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun aTransientTooSmallPassDoesNotEndTheAd() {
        val recorder = Recorder()
        val ad = overlay()
        ad.listener = recorder
        val container = container(width = 320, height = 400)
        ad.show(container)
        val host = container.getChildAt(0) as InlineAdView
        container.layout(0, 0, 100, 300) // a split-screen resize or an animated size, for a moment
        host.forceLayout()
        host.layout(0, 0, 100, 300)
        idle(100)
        container.layout(0, 0, 320, 400) // back to a size that fits
        host.forceLayout()
        host.layout(0, 0, 320, 400)
        idle(InlineAdView.unfitGraceMillis * 2)
        assertTrue(recorder.reasons.isEmpty())
        assertFalse(ad.hasEnded)
        // The next misfit is watched the same way.
        container.layout(0, 0, 100, 300)
        host.forceLayout()
        host.layout(0, 0, 100, 300)
        idle(InlineAdView.unfitGraceMillis * 2)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }
}
