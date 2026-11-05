package so.kontext.video

import android.graphics.Bitmap
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
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.FramePhase
import so.kontext.video.internal.frame.Origin
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A shown page cannot be replaced from under the ad: on a real WebView, a reload and a form POST never reach
 * shouldOverrideUrlLoading, so the bridge must either keep the document or end the ad.
 */
@RunWith(AndroidJUnit4::class)
class FrameNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    private fun evaluate(webView: WebView, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        main {
            webView.evaluateJavascript(script) {
                result = it
                done.countDown()
            }
        }
        assertTrue("JavaScript answered", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun navigateAfterShow(script: String) {
        lateinit var bridge: FrameBridge
        val shown = CountDownLatch(1)
        val navigated = CountDownLatch(1)
        val pages = AtomicInteger()
        val url = "https://frames.test/v2/frame/ad-1"
        main {
            bridge = FrameBridge(instrumentation.targetContext, "ad-1", url, false)
            val sdkClient = bridge.client
            // Serves the page locally, and forwards to the SDK's client what the test does not answer itself.
            bridge.webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse(
                    "text/html",
                    "UTF-8",
                    ByteArrayInputStream("<html><body><script>window.marker='fresh';</script></body></html>".toByteArray()),
                )

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val cancelled = sdkClient.shouldOverrideUrlLoading(view, request)
                    if (cancelled && pages.get() > 0) navigated.countDown()
                    return cancelled
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    sdkClient.onPageStarted(view, url, favicon)
                    if (pages.get() > 0) navigated.countDown()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (pages.incrementAndGet() == 1) {
                        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", Origin.of(url!!), true)
                        bridge.show()
                        shown.countDown()
                    }
                }
            }
            bridge.webView.loadUrl(url)
        }
        try {
            assertTrue("the page loaded", shown.await(15, TimeUnit.SECONDS))
            assertEquals("\"kept\"", evaluate(bridge.webView, "window.marker='kept'"))
            main { bridge.webView.evaluateJavascript(script, null) }
            assertTrue("the navigation happened or was refused", navigated.await(15, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            var state: FramePhase? = null
            main { state = bridge.state }
            val marker = if (state == FramePhase.ENDED) "\"kept\"" else evaluate(bridge.webView, "window.marker")
            assertTrue("the shown page was replaced silently: marker=$marker, state=$state", marker == "\"kept\"" || state == FramePhase.ENDED)
        } finally {
            main { bridge.hide() }
        }
    }

    @Test fun aReloadDoesNotSilentlyReplaceTheShownPage() = navigateAfterShow("location.reload()")

    @Test fun aFormPostDoesNotSilentlyReplaceTheShownPage() = navigateAfterShow(
        "var f=document.createElement('form'); f.method='POST'; f.action='https://frames.test/replaced'; document.body.appendChild(f); f.submit();",
    )
}
