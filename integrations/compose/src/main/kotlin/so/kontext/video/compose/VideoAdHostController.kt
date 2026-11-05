package so.kontext.video.compose

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.core.graphics.Insets
import androidx.core.view.doOnLayout
import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener

/**
 * The engine behind `VideoAdHost`: turns "this ad should be shown here" into show and dismiss. [set] an ad and
 * it is shown in [container] once the container is laid out (a linear ad from the container's Activity); set
 * another, or `null`, and the one shown is dismissed. The controller owns the ad's listener and forwards it to
 * [listener]. An ad shown elsewhere, or already ended, is left alone. It uses only the SDK's public API.
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
            listener?.onAdEnded(ad, reason)
        }
    }

    /** Shows [ad] here, replacing the one shown; `null` dismisses it. The same ad again does nothing. */
    fun set(ad: VideoAd?) {
        if (ad === this.ad) return
        val previous = this.ad
        this.ad = null
        if (previous != null) release(previous)
        if (ad == null || ad.wasShown || ad.hasEnded) return
        this.ad = ad
        ad.listener = forwarder
        container.doOnLayout { if (this.ad === ad && !ad.wasShown) show(ad) }
    }

    private fun show(ad: VideoAd) {
        try {
            if (ad.format == AdFormat.LINEAR) {
                val activity = container.context.findActivity() ?: throw VideoAdException.CannotShowNow()
                ad.show(activity)
            } else {
                ad.show(container, insets)
            }
        } catch (error: VideoAdException) {
            if (this.ad === ad) this.ad = null
            if (ad.listener === forwarder) ad.listener = null
            onShowFailed(ad, error)
        }
    }

    // A linear ad ends by itself or by the viewer (dismiss() does not apply to it): it keeps playing.
    private fun release(ad: VideoAd) {
        if (ad.wasShown && !ad.hasEnded && ad.format != AdFormat.LINEAR) {
            ad.dismiss() // its onAdEnded reaches the listener
        }
        if (!ad.wasShown && ad.listener === forwarder) ad.listener = null
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
