package so.kontext.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import kotlin.concurrent.thread

/** The calls that must run on the main thread say so when they do not; a callback hears one outcome. */
@RunWith(RobolectricTestRunner::class)
class MainSafetyTest : SdkTest() {
    private class Player : ContentPlayer {
        override val currentPositionMillis: Long = 0
        override val isPlaying: Boolean = true
    }

    @Test fun aPublicCallOffTheMainThreadIsAnIntegrationError() {
        val assertions = mutableListOf<String>()
        Environment.assertionFailure = { synchronized(assertions) { assertions += it } }
        val owner = owner()
        val unloaded = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), Environment.now(), owner)
        // The right format for each show: only the thread is wrong.
        val unloadedLinear = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.linear())), Environment.now(), owner)
        val container = container()
        val screen = activity()
        val calls = listOf<Pair<String, () -> Unit>>(
            "KontextVideo.user" to { KontextVideo.user = User { id = "u-1" } },
            "KontextVideo.regulatory" to { KontextVideo.regulatory = Regulatory { coppa = false } },
            "KontextVideo.variantId" to { KontextVideo.variantId = "b" },
            "KontextVideo.initialize()" to { KontextVideo.initialize(container.context, Configuration("test-token")) },
            "VideoAd.show(container)" to { unloaded.show(container) },
            "VideoAd.show(activity)" to { unloadedLinear.show(screen) },
            "VideoAd.dismiss()" to { unloaded.dismiss() },
            "VideoAd.reject()" to { unloaded.reject() },
            "VideoAds.track()" to { owner.track(Player()) },
            "VideoAds.destroy()" to { owner.destroy() },
        )
        for ((name, call) in calls) {
            thread { runCatching(call) }.join()
            assertTrue("$name: $assertions", assertions.lastOrNull()?.startsWith("KontextVideo: $name must be called on the main thread") == true)
        }
        assertEquals(calls.size, assertions.size)
        // On the main thread: nothing to say.
        KontextVideo.variantId = "c"
        assertEquals(calls.size, assertions.size)
    }

    @Test fun loadsCallbackHearsNoErrorWhenItsOwnOnAdThrows() {
        val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), Environment.now(), owner())
        val heard = mutableListOf<String>()
        ad.load(object : VideoAdCallback {
            override fun onAd(ad: VideoAd) {
                heard += "success"
                throw VideoAdException.ContentPaused() // the app's show() in onAd failing
            }

            override fun onError(error: VideoAdException) {
                heard += "error:${error::class.simpleName}"
            }
        })
        idle()
        page(ad, "ad.loaded")
        runCatching { idle() } // the app's own exception is the app's, on the main thread
        assertEquals(listOf("success"), heard)
    }
}
