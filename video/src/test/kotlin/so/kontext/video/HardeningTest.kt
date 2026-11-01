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
}
