package so.kontext.video.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.launch
import so.kontext.video.internal.platform.Environment
import org.robolectric.annotation.Config
import so.kontext.video.internal.device.UserAgent

/** The fallback user agent: the shape of an Android WebView's, `Mobile` on a phone only. The wait is ParityAdsTest's. */
@RunWith(RobolectricTestRunner::class)
class UserAgentTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun aPhonesFallbackHasTheWebViewsShapeAndSaysMobile() {
        val agent = UserAgent.fallbackUserAgent(context)
        assertTrue(agent, agent.startsWith("Mozilla/5.0 (Linux; Android "))
        assertTrue(agent, "; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0" in agent)
        assertTrue(agent, agent.endsWith(" Mobile Safari/537.36"))
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun aTabletsFallbackHasNoMobile() {
        val agent = UserAgent.fallbackUserAgent(context)
        assertFalse(agent, "Mobile" in agent)
        assertTrue(agent, agent.endsWith(" Safari/537.36"))
    }

    @Test fun aSlowLookupLeavesTheMainThreadFreeAndTheRequestTakesTheFallback() {
        val release = java.util.concurrent.CountDownLatch(1)
        UserAgent.read = { release.await(5, java.util.concurrent.TimeUnit.SECONDS); "the real one" }
        Environment.userAgentWaitMillis = 200
        Environment.context = context
        try {
            var agent: String? = null
            var mainRan = false
            Environment.scope.launch { agent = UserAgent.requestUserAgent(context) }
            // The lookup started on the main thread: had it blocked it, nothing posted after would run.
            android.os.Handler(android.os.Looper.getMainLooper()).post { mainRan = true }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("the main thread is free while the WebView answers", mainRan)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(250))
            assertEquals("past the wait: the fallback", UserAgent.fallbackUserAgent(context), agent)
        } finally {
            release.countDown()
            UserAgent.resetForTesting()
            Environment.reset()
        }
    }
}
