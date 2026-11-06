package so.kontext.video.internal.state

import so.kontext.video.VideoAd
import so.kontext.video.VideoAds
import java.util.Collections
import java.util.WeakHashMap

/**
 * Every `VideoAds` alive, held weakly: the kill switch reaches the ads of all of them, and a `VideoAds` nobody
 * destroys can still be collected. Main thread only, as every `VideoAds` call that touches it.
 */
internal object LiveVideoAds {
    private val live: MutableSet<VideoAds> = Collections.newSetFromMap(WeakHashMap())

    fun add(ads: VideoAds) {
        live += ads
    }

    fun remove(ads: VideoAds) {
        live -= ads
    }

    /**
     * The SDK was switched off: every unshown ad of every `VideoAds` ends now, without a notice (the server
     * knows), so a load in flight throws `Disabled` at once instead of waiting for its page, and a later
     * `destroy()` finds nothing to send `notUsed` for. An ad on screen plays on.
     */
    fun sdkWasDisabled() {
        for (owner in live.toList()) {
            owner.ads.values.toList().forEach { it.invalidate(VideoAd.InvalidationReason.Destroyed) }
        }
    }

    /** Forgets every `VideoAds`. Tests only: the SDK is process-wide. */
    fun resetForTesting() = live.clear()
}
