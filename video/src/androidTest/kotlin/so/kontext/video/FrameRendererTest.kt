package so.kontext.video

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.FrameEvent
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A shown page whose renderer stops answering ends the ad: a linear ad would have no other way out. */
@RunWith(AndroidJUnit4::class)
class FrameRendererTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun aShownPageThatStopsRespondingEndsTheAd() {
        lateinit var bridge: FrameBridge
        val shown = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var event: FrameEvent? = null
        val url = "https://frames.test/v2/frame/ad-1"
        // In a real window: the renderer's responsiveness is judged on the input events it is sent.
        val activity = instrumentation.startActivitySync(
            android.content.Intent(instrumentation.targetContext, TestHostActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as TestHostActivity
        instrumentation.runOnMainSync {
            bridge = FrameBridge(instrumentation.targetContext, "ad-1", url, false)
            bridge.onEvent = {
                event = it
                if (it is FrameEvent.Error) failed.countDown()
            }
            val sdkClient = bridge.webView.webViewClient
            bridge.webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream("<html><body>ad</body></html>".toByteArray()))

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = sdkClient.shouldOverrideUrlLoading(view, request)

                override fun onPageFinished(view: WebView, url: String?) {
                    if (bridge.state != FrameBridge.State.LOADING) return
                    bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", FrameBridge.Origin.of(url!!), true)
                    bridge.show()
                    shown.countDown()
                }
            }
            activity.setContentView(bridge.webView)
            bridge.webView.loadUrl(url)
        }
        try {
            assertTrue("the page loaded", shown.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { bridge.webView.evaluateJavascript("while (true) {}", null) }
            // The renderer is declared unresponsive after about five seconds of a blocked input event.
            for (attempt in 1..30) {
                if (failed.await(1, TimeUnit.SECONDS)) break
                instrumentation.runOnMainSync {
                    val time = android.os.SystemClock.uptimeMillis()
                    val down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0)
                    bridge.webView.dispatchTouchEvent(down)
                    down.recycle()
                }
            }
            assertTrue("the bridge gave up on the page", failed.count == 0L)
            assertEquals(405, (event as FrameEvent.Error).code)
            var cause: FrameBridge.FailureCause? = null
            instrumentation.runOnMainSync { cause = bridge.failureCause }
            assertEquals(FrameBridge.FailureCause.UNRESPONSIVE, cause)
        } finally {
            instrumentation.runOnMainSync {
                bridge.hide()
                activity.finish()
            }
        }
    }
}
