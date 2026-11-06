package so.kontext.video.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.core.graphics.Insets
import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener

/**
 * The engine behind `VideoAdHost`: turns "this ad should be shown here" into show and dismiss. [set] an ad and
 * it is shown in [container] once the container is laid out with a size (a linear ad from the container's
 * Activity, once laid out); set another, or `null`, and the one shown here is dismissed. The controller owns the
 * ad's listener and forwards it to [listener]. An ad shown elsewhere, before or after it was given, or already
 * ended, is left alone. One ad given is tried once: a failed show is not tried again until another is given, so
 * an [onShowFailed] that changes the app's state cannot make a loop of recompositions and failures. It uses only
 * the SDK's public API.
 *
 * ```
 *   set(ad) ──▶ adopted (listener = forwarder) ──▶ container attached, laid out, width and height > 0
 *                     │                                   │ (each layout pass until then; a 0×0 pass waits)
 *                     │                                   ▼  checked again once the pass ends (posted)
 *                     │                             shown elsewhere meanwhile? give it up : show(ad)
 *                     ▼                                   │ threw: onShowFailed, given up
 *   set(other or null): the ad shown here is dismissed    ▼
 *                                                     shownHere
 * ```
 */
@MainThread
internal class VideoAdHostController(private val container: ViewGroup) {
    /** Hears the ads this controller shows. */
    var listener: VideoAdListener? = null

    /** Kept clear of the app's controls, in pixels; read at `show`. */
    var insets: Insets = Insets.NONE

    /** `show` threw: nothing was shown, and the controller holds no ad. */
    var onShowFailed: (VideoAd, VideoAdException) -> Unit = { _, _ -> }

    /** The ad the controller was last given, until it ends or another replaces it. */
    var ad: VideoAd? = null
        private set

    /** The ad last passed to [set], shown or not, failed or not: the same one again does nothing. */
    private var requested: VideoAd? = null

    /** The ad this controller's own show put on screen: the only one it ever dismisses. */
    private var shownHere: VideoAd? = null

    /** Waits for the container's first layout with a size, while an adopted ad is not shown yet. */
    private var sizeWaiter: View.OnLayoutChangeListener? = null

    private val forwarder = object : VideoAdListener {
        override fun onAdImpression(ad: VideoAd) {
            listener?.onAdImpression(ad)
        }

        override fun onAdClicked(ad: VideoAd, click: VideoAd.Click) {
            listener?.onAdClicked(ad, click)
        }

        override fun onAdFailed(ad: VideoAd, error: VideoAdException) {
            listener?.onAdFailed(ad, error)
        }

        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
            if (this@VideoAdHostController.ad === ad) this@VideoAdHostController.ad = null
            if (shownHere === ad) shownHere = null
            listener?.onAdEnded(ad, reason)
        }
    }

    /** Shows [ad] here, replacing the one shown; `null` dismisses it. The same ad again does nothing. */
    fun set(ad: VideoAd?) {
        if (ad === requested) return
        requested = ad
        stopWaiting()
        val previous = this.ad
        this.ad = null
        if (previous != null) release(previous)
        if (ad == null || ad.wasShown || ad.hasEnded) return
        this.ad = ad
        ad.listener = forwarder
        showOnceSized(ad)
    }

    /**
     * Shows [ad] once the container can take it: attached and laid out, with a size for an overlay or pause ad
     * (a first pass at 0×0, an animated entrance or a lazy item's first pass, would make show throw
     * `CannotShowNow`). Each layout pass is a new chance; the show itself is posted, out of the layout pass, as
     * adding the ad's view during one asks for a layout inside it.
     */
    private fun showOnceSized(ad: VideoAd) {
        if (canShow(ad)) {
            show(ad)
            return
        }
        // Android marks the container laid out only after its layout listeners ran, and a stable layout makes no
        // second pass: the check waits for the pass to end. A pass at 0×0 keeps waiting.
        val waiter = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            container.post {
                if (this.ad === ad && sizeWaiter != null && canShow(ad)) {
                    stopWaiting()
                    show(ad)
                }
            }
        }
        sizeWaiter = waiter
        container.addOnLayoutChangeListener(waiter)
    }

    private fun canShow(ad: VideoAd): Boolean =
        container.isAttachedToWindow && container.isLaidOut && (ad.format == AdFormat.LINEAR || (container.width > 0 && container.height > 0))

    private fun stopWaiting() {
        sizeWaiter?.let(container::removeOnLayoutChangeListener)
        sizeWaiter = null
    }

    private fun show(ad: VideoAd) {
        if (ad.wasShown || ad.hasEnded) {
            // The app showed it elsewhere (or it ended) after it was given here: the adoption is given up.
            giveUp(ad)
            return
        }
        try {
            if (ad.format == AdFormat.LINEAR) {
                val activity = container.context.findActivity() ?: throw VideoAdException.CannotShowNow()
                ad.show(activity)
            } else {
                ad.show(container, insets)
            }
            if (ad.wasShown && !ad.hasEnded) shownHere = ad
        } catch (error: VideoAdException) {
            giveUp(ad)
            onShowFailed(ad, error)
        }
    }

    private fun giveUp(ad: VideoAd) {
        if (this.ad === ad) this.ad = null
        if (ad.listener === forwarder) ad.listener = null
    }

    // A linear ad ends by itself or by the viewer (dismiss() does not apply to it): it keeps playing.
    private fun release(ad: VideoAd) {
        if (ad === shownHere) {
            shownHere = null
            if (!ad.hasEnded && ad.format != AdFormat.LINEAR) ad.dismiss() // its onAdEnded reaches the listener
            return
        }
        if (ad.listener === forwarder) ad.listener = null
    }

    /** The Activity of [this] context, through any context wrappers; `null` for one of no Activity. */
    private fun Context.findActivity(): Activity? {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }
}
