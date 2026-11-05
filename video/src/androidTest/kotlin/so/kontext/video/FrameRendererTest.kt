package so.kontext.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.frame.FramePhase
import so.kontext.video.internal.frame.Origin

/**
 * A page whose renderer stops answering ends the ad: a linear ad would have no other way out. When Chromium
 * declares a renderer unresponsive is its own affair, and on an emulator it may take a minute or never come;
 * what is the SDK's is that a real WebView carries its client and that the client ends the ad. So the test
 * calls the registered client as Chromium would.
 */
@RunWith(AndroidJUnit4::class)
class FrameRendererTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val url = "https://frames.test/v2/frame/ad-1"

    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    private fun unresponsive(bridge: FrameBridge) {
        val client = WebViewCompat.getWebViewRenderProcessClient(bridge.webView)
        assertNotNull("the bridge registers a render-process client", client)
        client!!.onRenderProcessUnresponsive(bridge.webView, null)
    }

    @Test fun aRendererThatStopsRespondingEndsTheAdWithItsStatesCode() {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE))
        main {
            for ((isVideo, shown, code) in listOf(Triple(false, true, 405), Triple(false, false, 500), Triple(true, false, 400))) {
                val events = mutableListOf<FrameEvent>()
                val bridge = FrameBridge(instrumentation.targetContext, "ad-1", url, isVideo)
                bridge.onEvent = { events += it }
                if (shown) {
                    bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", Origin.of(url), true)
                    bridge.show()
                    events.clear()
                }
                unresponsive(bridge)
                assertEquals("shown=$shown video=$isVideo", code, (events.single() as FrameEvent.Error).code)
                assertEquals(FrameBridge.FailureCause.UNRESPONSIVE, bridge.failureCause)
                assertEquals(FramePhase.ENDED, bridge.state)
                unresponsive(bridge)
                assertTrue("an ended page is not failed twice", events.size == 1)
                bridge.webView.destroy()
            }
        }
    }
}
