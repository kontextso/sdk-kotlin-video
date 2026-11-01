package so.kontext.video

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.webkit.WebMessageCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.FrameEvent
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter

/** What a hostile page, a refusing system or an app that keeps its ads must not break. */
@RunWith(RobolectricTestRunner::class)
class HardeningTest : SdkTest() {
    // region A message that is not text

    @Test fun aBinaryMessageIsDroppedInEveryStateAndCountsAsOneBrokenEnvelope() {
        val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = false)
        val events = mutableListOf<FrameEvent>()
        bridge.onEvent = { events += it }
        val page = FrameBridge.Origin.of("https://frames.test")
        val binary = WebMessageCompat(byteArrayOf(1, 2, 3))
        bridge.receive(binary, FrameBridge.Origin.of("https://elsewhere.test"), true)
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

    // endregion

    // region A linear screen that cannot start

    /** An Activity whose launches fail, as with the SDK's Activity stripped from the merged manifest. */
    class RefusingActivity : Activity() {
        var failure: RuntimeException = ActivityNotFoundException()

        override fun startActivity(intent: Intent?) = throw failure
    }

    @Test fun aLaunchThatThrowsFailsTheAdOnceAndFreesTheSlot() {
        for (failure in listOf(ActivityNotFoundException("stripped"), SecurityException("not allowed"))) {
            val recorder = Recorder()
            val ad = linear(tracking = tracking("impression"))
            ad.listener = recorder
            val host = Robolectric.buildActivity(RefusingActivity::class.java).setup().get()
            host.failure = failure
            ad.show(host) // accepted: the outcome follows, after show returns
            assertTrue("nothing before show returns", recorder.heard.isEmpty())
            idle()
            assertEquals(failure.javaClass.simpleName, listOf("failed", "ended"), recorder.heard)
            assertEquals(listOf(VideoAd.EndReason.FAILED), recorder.reasons)
            assertTrue(recorder.errors.single() is VideoAdException.PresenterUnavailable)
            assertTrue("no impression", "impression" !in events)
            assertNull("the slot is free", Sdk.adOnScreen)
            idle(VideoAd.presentationTimeoutMillis + 1_000)
            assertEquals("exactly one outcome", 1, recorder.reasons.size)
            ErrorReporter.resetForTesting()
        }
        // Another ad shows.
        overlay().show(container())
    }

    // endregion
}
