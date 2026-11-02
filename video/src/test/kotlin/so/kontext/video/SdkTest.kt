package so.kontext.video

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.ad.handle
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.support.ActivityTracker
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.wire.AdPayload
import java.time.Duration

/**
 * What the Robolectric tests share: an SDK configured without `/config`, beacons recorded instead of sent, a
 * container in a visible window, and ads whose page reported `ad.loaded` without a WebView load.
 */
abstract class SdkTest {
    protected val owners = mutableListOf<VideoAds>()
    protected val beacons = mutableListOf<String>()

    /** The beacon events fired, by the `e` query value tests put in their URLs. */
    protected val events: List<String> get() = beacons.map { Regex("e=([^&]+)").find(it)?.groupValues?.get(1) ?: it }

    @Before fun setUpSdk() {
        Environment.context = ApplicationProvider.getApplicationContext()
        Environment.assertionFailure = {}
        Environment.sendBeacon = { url, _ -> beacons += url }
        Environment.userAgent = { "test-agent" }
        Environment.advertisingId = { null }
        Sdk.configureForTesting(Configuration("test-token"))
    }

    @After fun tearDownSdk() {
        VideoAd.presentationTimeoutMillis = 5_000
        VideoAd.dismissalTimeoutMillis = 10_000
        VideoAd.returnGraceMillis = 1_000
        VideoAd.makeBridge = ::FrameBridge
        owners.forEach { it.destroy() }
        Sdk.resetForTesting()
        Environment.reset()
        ActivityTracker.resetForTesting()
        ErrorReporter.resetForTesting()
    }

    protected fun owner(content: Content = Content("ep", "en")) = VideoAds(content).also { owners += it }

    protected fun idle(millis: Long = 0) {
        if (millis > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)) else shadowOf(Looper.getMainLooper()).idle()
    }

    /** A container in a visible window: Robolectric's plain Activity reports its window GONE even after setup. */
    protected fun container(width: Int = 320, height: Int = 400): FrameLayout {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = FrameLayout(activity)
        activity.setContentView(view)
        view.layout(0, 0, width, height)
        idle()
        view.layout(0, 0, width, height)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        return view
    }

    protected fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /** Tracking entries whose URLs say their event in `e=`. */
    protected fun tracking(vararg entries: String) = entries.joinToString(prefix = "[", postfix = "]") { entry ->
        val (event, extra) = entry.split("|").let { it[0] to it.getOrNull(1) }
        """{"event": "$event", "url": "https://t.test/b?e=$event${extra?.let { "&x=$it" } ?: ""}"${extra?.let { ", \"part\": \"$it\"" } ?: ""}}"""
    }

    internal fun loaded(payload: AdPayload, owner: VideoAds = owner()): VideoAd {
        val ad = VideoAd(payload, Environment.now(), owner)
        val bridge = FrameBridge(Environment.context!!, payload.id, payload.frameUrl, payload.creative == AdPayload.Creative.VIDEO)
        bridge.receive("""{"adId":"${payload.id}","type":"ad.loaded"}""", FrameBridge.Origin.of(payload.frameUrl), true)
        bridge.onEvent = { event -> ad.handle(event) } // as the ad wires the bridges it makes itself
        ad.loadedBridge = bridge
        ad.isLoaded = true
        return ad
    }

    protected fun overlay(owner: VideoAds = owner(), duration: String = "null", tracking: String = "[]") =
        loaded(AdPayload.parse(Fixtures.json(Fixtures.overlay(duration = duration, tracking = tracking))), owner)

    protected fun pause(owner: VideoAds = owner(), position: String = "top") =
        loaded(AdPayload.parse(Fixtures.json(Fixtures.pause(position = position))), owner)

    protected fun linear(owner: VideoAds = owner(), tracking: String = "[]") =
        loaded(AdPayload.parse(Fixtures.json(Fixtures.linear().replace("\"tracking\": []", "\"tracking\": $tracking"))), owner)

    /** Sends the ad's page a message, as its main frame. */
    protected fun page(ad: VideoAd, type: String, fields: String = "") {
        val bridge = ad.loadedBridge ?: return
        bridge.receive("""{"adId":"${ad.id}","type":"$type"${if (fields.isEmpty()) "" else ", $fields"}}""", FrameBridge.Origin.of(ad.payload.frameUrl), true)
    }

    /** A listener that records what it hears. */
    protected class Recorder : VideoAdListener {
        val heard = mutableListOf<String>()
        val reasons = mutableListOf<VideoAd.EndReason>()
        val clicks = mutableListOf<VideoAd.Click>()
        val errors = mutableListOf<VideoAdException>()

        override fun onAdImpression(ad: VideoAd) {
            heard += "impression"
        }

        override fun onAdClicked(ad: VideoAd, click: VideoAd.Click) {
            heard += "click"
            clicks += click
        }

        override fun onAdFailed(ad: VideoAd, error: VideoAdException) {
            heard += "failed"
            errors += error
        }

        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
            heard += "ended"
            reasons += reason
        }
    }
}
