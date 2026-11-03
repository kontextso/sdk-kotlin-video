package so.kontext.video.internal.ad

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.annotation.RestrictTo
import androidx.core.graphics.Insets
import androidx.core.view.doOnLayout
import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAdListener

/**
 * A place that shows whichever ad it is given, for declarative UI (`so.kontext:video-compose`): [set] an ad and
 * it is shown in [container] once the container is laid out (a linear ad from the container's Activity); set
 * another, or `null`, and the one shown is dismissed. The slot owns the ad's listener and forwards it to
 * [listener]. An ad shown elsewhere, or already ended, is left alone.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
@MainThread
public class VideoAdSlot(private val container: ViewGroup) {
    /** Hears the ads this slot shows. */
    public var listener: VideoAdListener? = null

    /** Kept clear of the app's controls, in pixels; read at `show`. */
    public var insets: Insets = Insets.NONE

    /** `show` threw: nothing was shown, and the slot holds no ad. */
    public var onShowFailed: (VideoAd, VideoAdException) -> Unit = { _, _ -> }

    /** The ad the slot was last given, until it ends or another replaces it. */
    public var ad: VideoAd? = null
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
            if (this@VideoAdSlot.ad === ad) this@VideoAdSlot.ad = null
            listener?.onAdEnded(ad, reason)
        }
    }

    /** Shows [ad] here, replacing the one shown; `null` dismisses it. The same ad again does nothing. */
    public fun set(ad: VideoAd?) {
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
                val activity = container.context.activity() ?: throw VideoAdException.PresenterUnavailable()
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

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
