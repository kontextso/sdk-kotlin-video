package so.kontext.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.wire.AdPayload
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What only a real WebView enforces: the origins it accepts for the bridge. */
@RunWith(AndroidJUnit4::class)
class WebViewSafetyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    @Before fun setUp() = main {
        Environment.context = instrumentation.targetContext
        Environment.sendBeacon = { _, _ -> }
        Sdk.configureForTesting(Configuration("test-token"))
    }

    @After fun reset() = main {
        Sdk.resetForTesting()
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
}
