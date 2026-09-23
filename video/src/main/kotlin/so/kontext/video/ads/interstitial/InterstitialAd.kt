package so.kontext.video.ads.interstitial

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import so.kontext.video.ads.AdPlacement
import so.kontext.video.model.VideoAd
import so.kontext.video.model.VideoAdEvent
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.tracking.AdTracker
import java.util.UUID

/**
 * The `interstitial` placement of one video: a full-screen video ad the SDK
 * presents itself, at a moment the app chooses (the model of MAX's
 * `MaxInterstitialAd`, AdMob's `InterstitialAd` and LevelPlay's
 * `LevelPlayInterstitialAd`: `isReady` → `show(activity)` → dismissed
 * callback). The SDK owns the screen, the AD badge and the Close chip; the
 * app only pauses its content before `show` and resumes it in `onDismissed`:
 *
 * ```kotlin
 * if (ads.interstitial.isReady) {
 *     player.pause()
 *     ads.interstitial.show(activity) { player.play() }
 * }
 * ```
 *
 * Close is server-driven: the chip counts down `playback.skip_after_ms` and
 * enables after it; with no `skip_after_ms` it enables when the video ends.
 * The screen never goes away on its own: after the video the last frame
 * stays up with Close enabled until the viewer taps it (the MAX / AdMob
 * end-card behaviour), so `onDismissed` always follows a user action.
 */
public class InterstitialAd internal constructor(
    videoId: String,
    emit: (VideoAdEvent) -> Unit,
) : AdPlacement(VideoAdFormat.INTERSTITIAL, videoId, emit) {
    /**
     * Appearance of the interstitial. Everything has a default.
     * @property closeCountdownFormat Label shown while the ad cannot be closed yet, with the remaining seconds: e.g. "Close in 5".
     * @property closeTitle Label once closing is allowed.
     */
    public data class Style(
        @ColorInt val backgroundColor: Int = Color.BLACK,
        val closeCountdownFormat: String = "Close in %d",
        val closeTitle: String = "Close",
        val badgeText: String = "AD",
        @ColorInt val textColor: Int = Color.WHITE,
        @ColorInt val chipBackgroundColor: Int = 0x73000000,
    )

    /** `true` once the Close chip is enabled: the server's `skip_after_ms` elapsed, or the video ended. */
    public var isClosable: Boolean = false
        private set

    private var style = Style()
    private var onDismissed: (() -> Unit)? = null
    private var screen: InterstitialAdActivity? = null
    private var launched = false
    private var completed = false
    private var ended = false
    private val token = UUID.randomUUID().toString()

    /**
     * Starts the SDK's own full-screen Activity over `activity`, plays the
     * video with sound (unless the server says `muted`), and consumes the
     * ad. Returns `false` when [isReady] is `false` or the screen is
     * already up.
     *
     * Pause your content before calling this and resume it in
     * `onDismissed`, which runs exactly once when the screen goes away: the
     * viewer tapped Close (before or after the video ended), the media
     * failed, or you called [hide]. It does not run for `VideoAds.destroy()`,
     * which you initiated.
     */
    public fun show(activity: Activity, style: Style = Style(), onDismissed: () -> Unit): Boolean {
        if (!isReady || launched) return false
        this.style = style
        this.onDismissed = onDismissed
        launched = true
        isClosable = false
        completed = false
        ended = false
        InterstitialAdActivity.register(token, this)
        activity.startActivity(Intent(activity, InterstitialAdActivity::class.java).putExtra(InterstitialAdActivity.EXTRA_TOKEN, token))
        return true
    }

    /**
     * Dismisses the ad because the app is leaving (viewer left the screen,
     * app going to background), including an end card the viewer has not
     * closed yet. Fires `skip` when the video had not finished. Runs
     * `onDismissed`. A no-op when nothing is on screen.
     */
    public override fun hide() {
        if (current != null) end(End.HIDDEN) else super.hide()
    }

    /** The Activity's content view exists: mount the ad into it. */
    internal fun attach(activity: InterstitialAdActivity, container: ViewGroup) {
        screen = activity
        if (isDestroyed || !present(container)) finishScreen()
    }

    /** The Activity went away on its own (system, task removed) while the ad was up. */
    internal fun onScreenGone(activity: InterstitialAdActivity) {
        if (screen !== activity) return
        screen = null
        if (current != null && !ended) {
            end(End.HIDDEN)
        } else {
            launched = false
            InterstitialAdActivity.unregister(token)
        }
    }

    override fun makeView(context: Context, ad: VideoAd): View = InterstitialAdView(
        context = context,
        ad = ad,
        style = style,
        onClick = ::onClick,
        onFirstFrame = {
            onCreativeRendered()
            tracker?.fireVideo(AdTracker.VideoEvent.START)
        },
        onProgress = { quartile ->
            when (quartile) {
                1 -> tracker?.fireVideo(AdTracker.VideoEvent.FIRST_QUARTILE)
                2 -> tracker?.fireVideo(AdTracker.VideoEvent.MIDPOINT)
                3 -> tracker?.fireVideo(AdTracker.VideoEvent.THIRD_QUARTILE)
            }
        },
        onClosable = { isClosable = true },
        onCloseTapped = { end(End.CLOSED) },
        onFailure = { message -> end(End.FAILED, message) },
        onComplete = ::didComplete,
    )

    override fun mount(view: View, container: ViewGroup): View {
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        (view as InterstitialAdView).play()
        return view
    }

    override fun willHide(userDismissed: Boolean) {
        (view as? InterstitialAdView)?.stop()
        if (userDismissed && !completed) tracker?.fireVideo(AdTracker.VideoEvent.SKIP)
    }

    override fun afterTeardown() {
        finishScreen()
        onDismissed = null
    }

    /** The video reached its end. The screen stays up on the last frame with Close enabled; only the viewer's tap ends the ad. */
    private fun didComplete() {
        val ad = current ?: return
        if (completed) return
        completed = true
        isClosable = true
        tracker?.fireVideo(AdTracker.VideoEvent.COMPLETE)
        (view as? InterstitialAdView)?.showEndState()
        emit(VideoAdEvent.Completed(VideoAdEvent.AdData(VideoAdFormat.INTERSTITIAL, videoId, ad.adId)))
    }

    private enum class End { CLOSED, FAILED, HIDDEN }

    /** Single exit: every way the screen can go away goes through here, so it is dismissed and `onDismissed` runs exactly once. */
    private fun end(end: End, message: String? = null) {
        val ad = current ?: return
        if (ended) return
        ended = true
        when (end) {
            // Closing the end card: `close` tracking, no `Closed` — the ad was not cut short, `Completed` already said so.
            End.CLOSED -> if (completed) hide(userDismissed = true) else onUserDismiss()
            End.FAILED -> {
                // Unplayable media: tear down without skip tracking and let the app move on.
                completed = true
                hide(userDismissed = false)
                emit(VideoAdEvent.Error(VideoAdEvent.ErrorData(videoId, "interstitial ad ${ad.adId}: $message")))
            }
            End.HIDDEN -> hide(userDismissed = true)
        }
        finishScreen()
        val callback = onDismissed
        onDismissed = null
        callback?.invoke()
    }

    private fun finishScreen() {
        launched = false
        InterstitialAdActivity.unregister(token)
        val screen = screen ?: return
        this.screen = null
        screen.finish()
    }
}
