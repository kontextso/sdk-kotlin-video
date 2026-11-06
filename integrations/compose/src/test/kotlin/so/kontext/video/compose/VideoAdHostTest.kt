package so.kontext.video.compose

import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.KontextVideo
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAds
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.AppActivities
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot
import java.time.Duration

/**
 * `VideoAdHost` in a real composition, as an app uses it: the ad given is shown once the host has a size, and
 * dismissed when the host leaves or is given `null`; a failed show is tried once per ad given. The controller's
 * own cases are in [VideoAdHostControllerTest]. Real ads with no server, as there.
 */
@RunWith(RobolectricTestRunner::class)
class VideoAdHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val owners = mutableListOf<VideoAds>()
    private val ended = mutableListOf<VideoAd.EndReason>()
    private val failures = mutableListOf<VideoAdException>()
    private var nextId = 0

    @Before fun setUpSdk() {
        Environment.context = ApplicationProvider.getApplicationContext()
        Environment.assertionFailure = {}
        Environment.sendBeacon = { _, _ -> }
        Environment.userAgent = { "test-agent" }
        Environment.advertisingId = { null }
        SdkInitializer.configureForTesting(KontextVideo.Configuration("test-token"))
    }

    @After fun tearDownSdk() {
        owners.forEach { it.destroy() }
        SdkInitializer.resetForTesting()
        Environment.reset()
        AppActivities.resetForTesting()
    }

    @Test fun theHostShowsTheAdOnceItHasASize() {
        val ad = pause()
        var isSized by mutableStateOf(false)
        setContent {
            VideoAdHost(ad, if (isSized) Modifier.fillMaxSize() else Modifier.size(0.dp), onShowFailed = { _, error -> failures += error })
        }
        settle()
        assertFalse("a 0×0 host waits", ad.wasShown)
        isSized = true
        settle()
        assertTrue(ad.wasShown)
        assertTrue(failures.isEmpty())
        assertNotNull(ScreenSlot.current)
    }

    @Test fun theHostLeavingTheCompositionDismissesTheAd() {
        var ad by mutableStateOf<VideoAd?>(null)
        var isHosted by mutableStateOf(true)
        setContent {
            if (isHosted) VideoAdHost(ad, Modifier.fillMaxSize(), onEnded = { _, reason -> ended += reason })
        }
        settle()
        val shown = pause()
        ad = shown
        settle()
        assertTrue(shown.wasShown)
        isHosted = false
        settle()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), ended)
        assertTrue(shown.hasEnded)
        assertNull(ScreenSlot.current)
    }

    @Test fun nullDismissesTheAd() {
        var ad by mutableStateOf<VideoAd?>(null)
        setContent {
            VideoAdHost(ad, Modifier.fillMaxSize(), onEnded = { _, reason -> ended += reason })
        }
        settle()
        val shown = pause()
        ad = shown
        settle()
        assertTrue(shown.wasShown)
        ad = null
        settle()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), ended)
        assertTrue(shown.hasEnded)
        assertNull(ScreenSlot.current)
    }

    @Test fun aFailedShowIsTriedOnceAndAgainAfterNull() {
        // A second host's ad cannot show while the first one's is on screen: the app has one ad slot.
        val first = pause()
        val second = pause()
        var firstAd by mutableStateOf<VideoAd?>(null)
        var secondAd by mutableStateOf<VideoAd?>(null)
        var failuresShown by mutableIntStateOf(0)
        var compositions = 0
        setContent {
            Box(Modifier.fillMaxSize()) {
                VideoAdHost(firstAd, Modifier.fillMaxSize())
                // The app's state, which its onShowFailed changes (an error message): each failure recomposes the
                // host with the same ad and a new callback.
                val shownSoFar = failuresShown
                SideEffect { compositions++ }
                VideoAdHost(
                    secondAd,
                    Modifier.fillMaxSize(),
                    onShowFailed = { _, error ->
                        failures += error
                        failuresShown = shownSoFar + 1
                    },
                )
            }
        }
        settle()
        firstAd = first
        settle()
        assertTrue(first.wasShown)
        secondAd = second
        settle()
        assertTrue(failures.single() is VideoAdException.AnotherAdShowing)
        // Recomposed with the same ad by the failure, then again with the slot freed: not tried again.
        firstAd = null
        settle()
        assertTrue(first.hasEnded)
        assertTrue("recomposed after the failure: $compositions", compositions >= 3)
        assertEquals("tried once", 1, failures.size)
        assertFalse(second.wasShown)
        // null, then the same ad again: tried again, and shown now that the slot is free.
        secondAd = null
        settle()
        secondAd = second
        settle()
        assertTrue(second.wasShown)
        assertEquals(1, failures.size)
    }

    /**
     * The composition, in a window that says it is visible: Robolectric reports the Activity's window GONE even
     * once resumed, and the SDK shows nothing in a hidden window.
     */
    private fun setContent(content: @Composable () -> Unit) {
        val attachInfo = ReflectionHelpers.getField<Any>(compose.activity.window.decorView, "mAttachInfo")
        ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE)
        compose.setContent(content)
    }

    /** Recomposes, then runs the view layout pass and what it posted, as a frame on a device would. */
    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        compose.waitForIdle()
    }

    private fun pause(): VideoAd {
        val id = "ad-p${nextId++}"
        val owner = VideoAds(KontextVideo.Content("ep", "en")).also { owners += it }
        val payload = AdPayload.parse(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"id": "$id", "format": "pause", "creative": "image", "trigger": {"kind": "manual"},
                 "price": {"value": 5.5, "currency": "EUR", "precision": "estimated"}, "expires": 600000,
                 "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/$id"}, "tracking": [],
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
