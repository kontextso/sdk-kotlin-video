package so.kontext.video.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.KontextVideo
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAds
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot
import java.util.Collections

/**
 * `VideoAdHost` on a device's real layout passes: an ad given in the host's very first composition is shown
 * once the host is laid out with a size. A real ad with no server, as in `VideoAdHostTest`.
 */
@RunWith(AndroidJUnit4::class)
class VideoAdHostDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val owners = mutableListOf<VideoAds>()
    private val failures: MutableList<VideoAdException> = Collections.synchronizedList(mutableListOf())

    @Before fun setUpSdk() {
        instrumentation.runOnMainSync {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { _, _ -> }
            SdkInitializer.configureForTesting(KontextVideo.Configuration("test-token"))
        }
    }

    @After fun tearDownSdk() {
        instrumentation.runOnMainSync {
            owners.forEach { it.destroy() }
            SdkInitializer.resetForTesting()
            Environment.reset()
        }
    }

    @Test fun anAdGivenInTheFirstCompositionIsShown() {
        lateinit var ad: VideoAd
        compose.runOnUiThread { ad = pause() }
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                VideoAdHost(ad, Modifier.fillMaxSize(), onShowFailed = { _, error -> failures += error })
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) { compose.runOnUiThread<Boolean> { ad.wasShown } || failures.isNotEmpty() }
        assertTrue("no failed show: $failures", failures.isEmpty())
        compose.runOnUiThread {
            assertTrue(ad.wasShown)
            assertNotNull(ScreenSlot.current)
        }
    }

    private fun pause(): VideoAd {
        val owner = VideoAds(KontextVideo.Content("ep", "en")).also { owners += it }
        val payload = AdPayload.parse(
            Json.parseToJsonElement(
                """{"id": "ad-p0", "format": "pause", "creative": "image", "trigger": {"kind": "manual"},
                 "price": {"value": 5.5, "currency": "EUR", "precision": "estimated"}, "expires": 600000,
                 "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/ad-p0"}, "tracking": [],
                 "position": "top", "sizes": [{"width": 300, "height": 250, "scalable": false}]}""",
            ),
        )
        val ad = VideoAd(payload, Environment.now(), owner)
        val bridge = FrameBridge(Environment.context!!, payload.id, payload.frameUrl, payload.creative == AdPayload.Creative.VIDEO)
        bridge.receive("""{"adId":"${payload.id}","type":"ad.loaded"}""", Origin.of(payload.frameUrl), true)
        bridge.onEvent = { event -> ad.handle(event) }
        ad.loadedBridge = bridge
        ad.state.markLoaded()
        return ad
    }
}
