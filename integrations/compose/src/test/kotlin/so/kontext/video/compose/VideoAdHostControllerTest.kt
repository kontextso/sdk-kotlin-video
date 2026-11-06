package so.kontext.video.compose

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.KontextVideo
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener
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
 * The controller behind `VideoAdHost`: the ad it is given is shown, replaced or dismissed. Real ads with no
 * server, as in :video's tests: made from a payload, their page's messages sent by hand.
 */
@RunWith(RobolectricTestRunner::class)
class VideoAdHostControllerTest {
    private val owners = mutableListOf<VideoAds>()
    private val recorder = Recorder()
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

    private fun slot() = VideoAdHostController(container()).also { slot ->
        slot.listener = recorder
        slot.onShowFailed = { _, error -> failures += error }
    }

    @Test fun anAdGivenIsShownInTheContainerAndHeard() {
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        assertTrue(ad.wasShown)
        assertSame(ad, slot.ad)
        page(ad, "ad.close")
        assertEquals(listOf(VideoAd.EndReason.CLOSED), recorder.reasons)
        assertNull("an ended ad leaves the slot", slot.ad)
    }

    @Test fun nullDismissesTheAdShown() {
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        slot.set(null)
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        assertNull(ScreenSlot.current)
    }

    @Test fun anotherAdReplacesTheOneShown() {
        val slot = slot()
        val first = pause()
        val second = pause()
        slot.set(first)
        laidOut()
        slot.set(second)
        laidOut()
        assertEquals("the first was dismissed", listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
        assertTrue(second.wasShown)
        assertSame(second, slot.ad)
    }

    @Test fun theSameAdAgainDoesNothing() {
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        slot.set(ad)
        laidOut()
        assertTrue(recorder.reasons.isEmpty())
        assertSame(ad, slot.ad)
    }

    @Test fun anAdThatCannotBeShownIsReportedAndNotHeld() {
        val elsewhere = pause()
        elsewhere.show(container()) // the app's one slot is taken
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        assertTrue(failures.single() is VideoAdException.AnotherAdShowing)
        assertNull(slot.ad)
        assertFalse(ad.wasShown)
        assertNull("the ad's listener is not left on the slot", ad.listener)
    }

    @Test fun anAdShownElsewhereIsLeftAlone() {
        val ad = pause()
        ad.show(container())
        val slot = slot()
        slot.set(ad)
        slot.set(null)
        assertFalse("not dismissed by a slot that did not show it", ad.hasEnded)
        assertNull(slot.ad)
    }

    @Test fun anAdShownElsewhereAfterItWasGivenIsLeftAlone() {
        val slot = VideoAdHostController(FrameLayout(activity())) // not laid out yet
        val ad = pause()
        slot.set(ad)
        ad.show(container()) // the app shows it elsewhere before the slot's layout
        slot.set(null)
        assertFalse("not dismissed by a slot that did not show it", ad.hasEnded)
        assertNull(slot.ad)
    }

    @Test fun aFailedShowIsNotTriedAgainForTheSameAd() {
        val elsewhere = pause()
        elsewhere.show(container()) // the app's one slot is taken
        val slot = slot()
        val ad = pause()
        slot.set(ad)
        laidOut()
        // A recomposition passes the same ad again (an onShowFailed that changed the app's state): no new try.
        slot.set(ad)
        laidOut()
        slot.set(ad)
        laidOut()
        assertEquals(1, failures.size)
        // Another ad, or the same one after another, is a new request.
        elsewhere.dismiss()
        slot.set(null)
        slot.set(ad)
        laidOut()
        assertTrue(ad.wasShown)
    }

    @Test fun aContainerLaidOutWithNoSizeWaitsForOne() {
        val container = container()
        container.layout(0, 0, 0, 0)
        val slot = VideoAdHostController(container).also { it.onShowFailed = { _, error -> failures += error } }
        val ad = pause()
        slot.set(ad)
        laidOut()
        assertTrue("a 0×0 pass is not a failure", failures.isEmpty())
        assertFalse(ad.wasShown)
        container.layout(0, 0, 320, 400)
        laidOut()
        assertTrue(ad.wasShown)
        assertTrue(failures.isEmpty())
    }

    @Test fun anAdTakenAwayBeforeLayoutIsNeverShown() {
        val slot = VideoAdHostController(android.widget.FrameLayout(activity())) // not laid out yet
        val ad = pause()
        slot.set(ad)
        slot.set(null)
        laidOut()
        assertFalse(ad.wasShown)
        assertNull("its listener is given back", ad.listener)
    }

    @Test fun aLinearAdStartsFromTheContainersActivityAndIsNotDismissed() {
        val slot = slot()
        val ad = linear()
        slot.set(ad)
        laidOut()
        assertTrue(ad.wasShown)
        slot.set(null)
        assertFalse("a linear ad ends by itself or by the viewer", ad.hasEnded)
        ad.end(VideoAd.EndReason.COMPLETED)
        assertEquals("its end still reaches the app", listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
    }

    @Test fun anEpisodesAdsAreSetUpOnceRememberedAndDestroyedWhenForgottenOrAbandoned() {
        var setUps = 0
        val remembered = EpisodeAds(VideoAds(KontextVideo.Content("ep", "en")).also { owners += it }) { setUps += 1 }
        assertEquals("not before the composition applies", 0, setUps)
        remembered.onRemembered()
        remembered.onRemembered()
        assertEquals(1, setUps)
        remembered.onForgotten()
        assertTrue(remembered.ads.isDestroyed)
        val abandoned = EpisodeAds(VideoAds(KontextVideo.Content("ep", "en")).also { owners += it }) { setUps += 1 }
        abandoned.onAbandoned()
        assertTrue("a composition abandoned before it applied leaves nothing registered", abandoned.ads.isDestroyed)
        assertEquals(1, setUps)
    }

    /** Runs the layout pass Android makes after `show` added the ad. */
    private fun laidOut() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))

    /** A container in a visible window: Robolectric's plain Activity reports its window GONE even after setup. */
    private fun container(): FrameLayout {
        val activity = activity()
        val view = FrameLayout(activity)
        activity.setContentView(view)
        view.layout(0, 0, 320, 400)
        shadowOf(Looper.getMainLooper()).idle()
        view.layout(0, 0, 320, 400)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        return view
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun loaded(json: String): VideoAd {
        val owner = VideoAds(KontextVideo.Content("ep", "en")).also { owners += it }
        val payload = AdPayload.parse(kotlinx.serialization.json.Json.parseToJsonElement(json))
        val ad = VideoAd(payload, Environment.now(), owner)
        val bridge = FrameBridge(Environment.context!!, payload.id, payload.frameUrl, payload.creative == AdPayload.Creative.VIDEO)
        bridge.receive("""{"adId":"${payload.id}","type":"ad.loaded"}""", Origin.of(payload.frameUrl), true)
        bridge.onEvent = { event -> ad.handle(event) }
        ad.loadedBridge = bridge
        ad.state.markLoaded()
        return ad
    }

    private fun pause(): VideoAd {
        val id = "ad-p${nextId++}"
        return loaded(
            """{"id": "$id", "format": "pause", "creative": "image", "trigger": {"kind": "manual"},
             "price": {"value": 5.5, "currency": "EUR", "precision": "estimated"}, "expires": 600000,
             "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/$id"}, "tracking": [],
             "position": "top", "sizes": [{"width": 300, "height": 250, "scalable": false}]}""",
        )
    }

    private fun linear(): VideoAd = loaded(
        """{"id": "ad-3", "format": "linear", "creative": "video", "trigger": {"kind": "manual"},
         "price": {"value": 12.0, "currency": "USD", "precision": "exact"}, "expires": 600000,
         "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/ad-3"}, "tracking": [],
         "duration": 30000, "skipOffset": 5000}""",
    )

    /** Sends the ad's page a message, as its main frame. */
    private fun page(ad: VideoAd, type: String) {
        ad.loadedBridge?.receive("""{"adId":"${ad.id}","type":"$type"}""", Origin.of(ad.payload.frameUrl), true)
    }

    private class Recorder : VideoAdListener {
        val reasons = mutableListOf<VideoAd.EndReason>()

        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
            reasons += reason
        }
    }
}
