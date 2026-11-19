package so.kontext.video

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.SdkInitializer
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.SdkState

/** The facade with no server: what the app sets, the version, a second initialize, a `VideoAds` with no id or destroyed, memory pressure. */
@RunWith(RobolectricTestRunner::class)
class KontextVideoTest : SdkTest() {
    private var callbackError: VideoAdException? = null

    @Test fun theFacadeHoldsWhatTheAppSets() {
        val user = User("u-1")
        KontextVideo.user = user
        KontextVideo.regulatory = Regulatory { coppa = true }
        assertSame(user, KontextVideo.user)
        assertEquals(true, KontextVideo.regulatory?.coppa)
        assertEquals(KontextVideo.State.Enabled, KontextVideo.state)
        var asserted: String? = null
        Environment.assertionFailure = { asserted = it }
        KontextVideo.user = User("")
        assertNotNull("an empty id is a programmer error", asserted)
        assertTrue(KontextVideo.VERSION.isNotEmpty())
    }

    @Test fun theVersionIsReadAtRunTimeAndNoBuildConfigIsPublic() {
        // A field, not a compile-time constant an app would inline: the version the app ships with is the one read.
        val field = KontextVideo::class.java.getField("VERSION")
        assertTrue(java.lang.reflect.Modifier.isStatic(field.modifiers))
        assertEquals(so.kontext.video.internal.SDK_VERSION, field.get(null))
        assertTrue(
            "no generated BuildConfig in the SDK's public package",
            runCatching { Class.forName("so.kontext.video.BuildConfig") }.isFailure,
        )
    }

    @Test fun aSecondInitializeIsIgnored() {
        val configuration = SdkState.current.configuration
        KontextVideo.initialize(ApplicationProvider.getApplicationContext(), Configuration("other"))
        assertSame(configuration, SdkState.current.configuration)
    }

    @Test fun formatWithACallbackDeliversTheError() {
        SdkInitializer.disable("paused")
        var error: VideoAdException? = null
        owner().format(
            AdFormat.PAUSE,
            object : VideoAdCallback {
                override fun onAd(ad: VideoAd) = org.junit.Assert.fail("disabled")

                override fun onError(error: VideoAdException) {
                    this@KontextVideoTest.callbackError = error
                }
            },
        )
        idle()
        error = callbackError
        assertTrue(error is VideoAdException.Disabled)
    }

    @Test fun aVideoAdsWithoutAnIdAsksAndAfterDestroyRefusesEverything() {
        var asserted: String? = null
        Environment.assertionFailure = { asserted = it }
        val ads = owner(Content("", "en"))
        assertNotNull(asserted)
        assertTrue("ep" !in ads.toString())
        ads.destroy()
        assertTrue(ads.isDestroyed)
        ads.track(object : ContentPlayer {
            override val currentPositionMillis = 0L
            override val isPlaying = true
        })
        assertFalse(ads.playback.isTracking)
    }

    @Test fun memoryPressureReleasesExpiredAdsOnly() {
        var now = 0L
        Environment.now = { now }
        val owner = owner()
        val ad = overlay(owner)
        owner.ads[AdFormat.OVERLAY] = ad
        Environment.context!!.applicationContext.let { (it as android.app.Application).onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
        assertNull("valid: kept", ad.invalidationReason)
        now = 10_000_000
        (Environment.context as android.app.Application).onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(VideoAd.InvalidationReason.Expired, ad.invalidationReason)
    }
}
