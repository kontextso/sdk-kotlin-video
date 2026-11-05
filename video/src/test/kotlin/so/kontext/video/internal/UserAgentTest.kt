package so.kontext.video.internal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
