package so.kontext.video

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.ad.handle
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.support.Environment
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The frame protocol end to end on a real WebView, with the page's own JavaScript: it reports `ad.loaded`
 * through `KontextAndroid`, which `load()` waits for; `show()` sends it `ad.show`; it answers `video.start`,
 * which records the impression. Nothing is injected on the SDK's side.
 */
@RunWith(AndroidJUnit4::class)
class FrameProtocolTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val url = "https://frames.test/v2/frame/ad-1"
    private val beacons = Collections.synchronizedList(mutableListOf<String>())

    private val page = """
        <html><body><script>
          window.addEventListener('message', function (event) {
            var message = event.data;
            if (typeof message === 'string') { try { message = JSON.parse(message); } catch (e) { return; } }
            if (!message || message.adId !== 'ad-1' || message.type !== 'ad.show') return;
            window.shownWith = JSON.stringify(message);
            KontextAndroid.postMessage(JSON.stringify({ adId: 'ad-1', type: 'video.start', playhead: 0 }));
          });
          KontextAndroid.postMessage(JSON.stringify({ adId: 'ad-1', type: 'ad.loaded' }));
        </script></body></html>
    """.trimIndent()

    private val payload = """
        {"id": "ad-1", "format": "overlay", "creative": "video", "trigger": {"kind": "manual"},
         "price": {"value": 8.0, "currency": "USD", "precision": "exact"}, "expires": 600000,
         "rendering": {"kind": "frame", "url": "$url"},
         "tracking": [{"event": "impression", "url": "https://t.test/b?e=impression"}],
         "position": "bottom", "sizes": [{"width": 320, "height": 80}], "duration": null}
    """.trimIndent()

    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    @After fun reset() = main {
        Sdk.resetForTesting()
        Environment.reset()
    }

    @Test fun thePagesOwnMessagesLoadShowAndCountTheAd() {
        // A WebView older than the message listener (an old emulator image's own) never hears the page: the SDK
        // refuses such an ad by design, and there is no protocol to test.
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        val activity = instrumentation.startActivitySync(
            android.content.Intent(instrumentation.targetContext, TestHostActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as TestHostActivity
        lateinit var ad: VideoAd
        lateinit var owner: VideoAds
        lateinit var container: FrameLayout
        val loaded = CountDownLatch(1)
        val impression = CountDownLatch(1)
        var loadError: VideoAdException? = null
        main {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { beacon, _ -> beacons += beacon }
            Sdk.configureForTesting(Configuration("test-token"))
            owner = VideoAds(Content("ep", "en"))
            ad = VideoAd(AdPayload.parse(Json.parseToJsonElement(payload)), Environment.now(), owner)
            // The ad's own bridge, its page served here instead of from the network.
            val bridge = FrameBridge(instrumentation.targetContext, ad.id, url, isVideo = true)
            bridge.onEvent = { event -> ad.handle(event) }
            val sdkClient = bridge.webView.webViewClient
            bridge.webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(page.toByteArray()))

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = sdkClient.shouldOverrideUrlLoading(view, request)
            }
            ad.loadedBridge = bridge
            ad.listener = object : VideoAdListener {
                override fun onAdImpression(ad: VideoAd) = impression.countDown()
            }
            ad.load(object : VideoAdsCallback<VideoAd> {
                override fun onSuccess(result: VideoAd) = loaded.countDown()

                override fun onError(error: VideoAdException) {
                    loadError = error
                    loaded.countDown()
                }
            })
        }
        try {
            assertTrue("load() returned: the page's ad.loaded arrived", loaded.await(20, TimeUnit.SECONDS))
            assertEquals(null, loadError)
            main {
                container = FrameLayout(activity)
                activity.setContentView(container)
            }
            instrumentation.waitForIdleSync()
            main { ad.show(container) }
            assertTrue("the page got ad.show and its video.start counted the ad", impression.await(20, TimeUnit.SECONDS))
            assertTrue("the impression beacon went: $beacons", beacons.any { "e=impression" in it })
        } finally {
            main {
                owner.destroy()
                activity.finish()
            }
        }
    }
}
