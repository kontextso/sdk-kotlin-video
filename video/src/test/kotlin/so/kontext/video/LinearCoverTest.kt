package so.kontext.video

import android.app.AlertDialog
import android.view.View
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.internal.rendering.LinearAdActivity
import so.kontext.video.internal.rendering.ViewabilityMeter
import so.kontext.video.internal.support.Environment

/**
 * A linear ad under another screen, as the Swift suite has it: another Activity over its screen (paused, its
 * window still drawn) reads as exposure 0 on every tick, so the page pauses, no viewable fires, and the dwell
 * starts over once it goes. A dialog over it does not cover it: the viewer sees the ad around it.
 */
@RunWith(RobolectricTestRunner::class)
class LinearCoverTest : SdkTest() {
    private var now = 0L

    @org.junit.After fun restoreInterval() {
        ViewabilityMeter.intervalMillis = 250
    }

    /** Shows a playing linear ad and brings its screen to the front; returns the screen and the exposures the page hears. */
    private fun presented(): Triple<VideoAd, ActivityController<LinearAdActivity>, MutableList<Double>> {
        ViewabilityMeter.intervalMillis = 1_000_000 // the test ticks the meter itself
        Environment.now = { now }
        val ad = linear(tracking = tracking("viewable"))
        val exposures = mutableListOf<Double>()
        ad.loadedBridge!!.onSend = { type, message -> if (type == "ad.exposure") exposures += message["exposedPercentage"]!!.jsonPrimitive.double }
        val host = activity()
        ad.show(host)
        val screen = Robolectric.buildActivity(LinearAdActivity::class.java, shadowOf(host).nextStartedActivity).create().start().resume().visible()
        idle()
        // Robolectric reports the window GONE, and measures a WebView at no size: a real one is drawn, visible.
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(screen.get().window.decorView, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        page(ad, "video.start", """"playhead": 0""")
        return Triple(ad, screen, exposures)
    }

    /** Thirty ticks a tenth of a second apart: 3 s, more than a video's 2 s dwell. */
    private fun ticks(ad: VideoAd, count: Int = 30) = repeat(count) {
        now += 100
        ad.meter!!.tick()
    }

    /** The page fills its screen, as a real WebView does: Robolectric's ignores its layout, so its frame is set. */
    private fun drawn(ad: VideoAd) {
        val webView = ad.loadedBridge!!.webView
        var node = webView.parent as? View
        while (node != null) {
            node.layout(0, 0, 320, 480)
            node = node.parent as? View
        }
        ReflectionHelpers.setField(webView, "mRight", 320)
        ReflectionHelpers.setField(webView, "mBottom", 480)
    }

    @Test fun anotherActivityOverTheLinearAdReadsAsExposure0AndTheAdGoesOnOnceItGoes() {
        val (ad, screen, exposures) = presented()
        drawn(ad)
        ad.meter!!.tick()
        assertTrue("on screen: $exposures", (exposures.lastOrNull() ?: 0.0) > 0.5)
        // Another Activity on top, translucent or a sheet: the ad's screen pauses, its window still drawn.
        screen.pause()
        exposures.clear()
        ticks(ad)
        assertTrue("still in its window", ad.loadedBridge!!.webView.isAttachedToWindow)
        assertEquals("the page hears exposure 0 and pauses", listOf(0.0), exposures)
        assertNull("time under the cover is not counted as visible", ad.meter!!.verdict)
        assertFalse(ad.hasEnded)
        assertTrue("viewable" !in events)
        // The cover goes: exposed again, and the dwell starts over.
        screen.resume()
        drawn(ad)
        ticks(ad, 19)
        assertTrue("exposed again: $exposures", (exposures.lastOrNull() ?: 0.0) > 0.5)
        assertNull("the dwell starts over: 1.9 s are not 2", ad.meter!!.verdict)
        ticks(ad, 2)
        assertEquals(ViewabilityMeter.Verdict.VIEWABLE, ad.meter!!.verdict)
        assertEquals(listOf("viewable"), events)
    }

    @Test fun aDialogOverTheLinearAdIsMeasuredAsUsual() {
        val (ad, screen, exposures) = presented()
        val dialog = AlertDialog.Builder(screen.get()).setMessage("a question").show()
        idle()
        assertTrue(dialog.isShowing)
        drawn(ad)
        ticks(ad)
        assertTrue("seen around the dialog: $exposures", exposures.isNotEmpty() && exposures.all { it > 0.5 })
        assertEquals(ViewabilityMeter.Verdict.VIEWABLE, ad.meter!!.verdict)
        assertEquals(listOf("viewable"), events)
        dialog.dismiss()
    }
}
