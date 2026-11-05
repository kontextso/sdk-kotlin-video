package so.kontext.video.internal.screen

import so.kontext.video.VideoAd
import so.kontext.video.VideoAds

/**
 * The app's one ad on screen, from any `VideoAds`: taken when `show` commits, freed when `onAdEnded` is
 * delivered or when `show` could not put the ad on screen after all. The slot is taken and freed here and
 * nowhere else; it retains the ad until then, whatever the app does with its reference, and the owner's showing
 * ad follows it.
 *
 * ```
 *   show commits ──► take: current = ad, owner.adDidBeginShowing (before any app code runs)
 *   onAdEnded next ─► release: current = null, owner.adDidEnd
 *   show refused ──► cancel: current = null, owner.adDidNotShow (the ad held again)
 * ```
 */
internal object ScreenSlot {
    /** The ad on screen in the whole app, or `null`: a `show` meanwhile throws `AnotherAdShowing`. */
    var current: VideoAd? = null
        private set

    /** `show` committed: [ad] takes the slot and [owner] hears it is showing, before any app code runs. */
    fun take(ad: VideoAd, owner: VideoAds?) {
        current = ad
        owner?.adDidBeginShowing(ad)
    }

    /** `onAdEnded` is next: the slot is free and the owner stops following the ad. */
    fun release(ad: VideoAd) {
        if (current === ad) current = null
        ad.owner.get()?.adDidEnd(ad)
    }

    /** `show` could not put [ad] on screen after it committed: the slot is free, the owner holds the ad again. */
    fun cancel(ad: VideoAd, wasHeld: Boolean) {
        if (current === ad) current = null
        ad.owner.get()?.adDidNotShow(ad, wasHeld)
    }

    /** Tests only: the slot is free. */
    fun resetForTesting() {
        current = null
    }
}
