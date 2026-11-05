package so.kontext.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What only a real WebView enforces: the origins it accepts for the bridge, and the thread it is used on. */
@RunWith(AndroidJUnit4::class)
class WebViewSafetyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    @Before fun setUp() = main {
        Environment.context = instrumentation.targetContext
        Environment.sendBeacon = { _, _ -> }
        SdkInitializer.configureForTesting(Configuration("test-token"))
    }

    @After fun reset() = main {
        SdkInitializer.resetForTesting()
        Environment.reset()
    }

    /** An overlay whose frame URL skipped the payload's checks, as a bug in them would let one through. */
    private fun ad(owner: VideoAds, frameUrl: String) = VideoAd(
        AdPayload(
            id = "ad-1", format = AdFormat.OVERLAY, creative = AdPayload.Creative.IMAGE,
            price = VideoAd.Price(8.0, "USD", VideoAd.Price.Precision.EXACT), expiresMillis = 600_000,
            frameUrl = frameUrl, tracking = emptyList(), position = AdPayload.Position.BOTTOM,
            sizes = listOf(AdPayload.Size(320, 80)), durationMillis = null, skipOffsetMillis = null,
        ),
        Environment.now(),
        owner,
    )

    @Test fun anOriginTheWebViewRefusesFailsTheLoadInsteadOfCrashing() {
        // Only a WebView with the message listener checks the origin; an older one (an old emulator image's own)
        // never makes the bridge at all.
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        for (url in listOf("ftp://frames.test/a", "myapp://frames.test/a", "https://frames.test:99999/a")) {
            val done = CountDownLatch(1)
            var result: Result<Unit>? = null
            main {
                val owner = VideoAds(Content("ep", "en"))
                val ad = ad(owner, url)
                Environment.scope.launch {
                    result = runCatching { ad.load() }
                    owner.destroy()
                    done.countDown()
                }
            }
            assertTrue(url, done.await(10, TimeUnit.SECONDS))
            val error = result!!.exceptionOrNull()
            assertTrue("$url: $error", error is VideoAdException.CreativeFailed)
            assertEquals(url, 500, (error as VideoAdException.CreativeFailed).code)
        }
    }

    /** A coroutine on the I/O dispatcher, as `viewModelScope.launch(Dispatchers.IO)` runs one; its outcome. */
    private fun <T> onIo(work: suspend () -> T): Result<T> = runBlocking { withContext(Dispatchers.IO) { runCatching { work() } } }

    @Test fun loadCalledFromTheIoDispatcherMakesItsWebViewOnTheMainThread() {
        FrameBridge.loadTimeoutMillis = 500
        Environment.isAppInForeground = { true }
        lateinit var owner: VideoAds
        lateinit var ad: VideoAd
        main {
            owner = VideoAds(Content("ep", "en"))
            ad = ad(owner, "https://frames.test/v2/frame/ad-1")
        }
        val log = java.util.Collections.synchronizedList(mutableListOf<String>())
        Logger.sink = { _, message -> log += message }
        try {
            // Nothing serves the page: it fails as a creative, and its WebView was made (on the main thread).
            val error = onIo { ad.load() }.exceptionOrNull()
            assertTrue("$error", error is VideoAdException.CreativeFailed)
            assertTrue("$log", log.none { "no WebView" in it })
        } finally {
            Logger.sink = null
            FrameBridge.loadTimeoutMillis = 10_000
            main { owner.destroy() }
        }
    }

    @Test fun formatCalledFromTheIoDispatcherReleasesAnExpiredAdsWebViewOnTheMainThread() {
        lateinit var owner: VideoAds
        main {
            // Nothing listens there: the request fails at once.
            SdkInitializer.resetForTesting()
            SdkInitializer.configureForTesting(Configuration("test-token") { adServerUrl = "http://127.0.0.1:9" })
            owner = VideoAds(Content("ep", "en"))
            // A held ad that expired, whose WebView the next request releases.
            val expired = VideoAd(ad(owner, "https://frames.test/v2/frame/ad-1").payload, Environment.now() - 700_000, owner)
            expired.loadedBridge = FrameBridge(instrumentation.targetContext, "ad-1", "https://frames.test/v2/frame/ad-1", false)
            owner.ads[AdFormat.OVERLAY] = expired
        }
        try {
            val error = onIo { owner.format(AdFormat.OVERLAY) }.exceptionOrNull()
            assertTrue("$error", error is VideoAdException.Request)
        } finally {
            main { owner.destroy() }
        }
    }
}
