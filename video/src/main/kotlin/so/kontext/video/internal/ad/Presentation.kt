package so.kontext.video.internal.ad

import android.view.ViewGroup
import so.kontext.video.VideoAd
import so.kontext.video.VideoAds
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.rendering.ActiveTimeClock
import so.kontext.video.internal.rendering.AdHostView
import so.kontext.video.internal.rendering.LinearAdActivity
import so.kontext.video.internal.rendering.ViewabilityMeter
import java.lang.ref.WeakReference

/**
 * One ad on screen, from `show` to `onAdEnded`: the app's one slot, what is on screen (an overlay's or pause
 * ad's host view, or a linear ad's screen) and what measures it (the viewability meter, the clock). Made when
 * `show` commits, released when `onAdEnded` is delivered; the slot is taken and freed here and nowhere else,
 * and `Sdk.adOnScreen` and the owner's showing ad follow it.
 */
internal class Presentation private constructor(
    /** Retained from `show` to `onAdEnded`, whatever the app does with its own reference. */
    val ad: VideoAd,
    owner: VideoAds?,
) {
    private val owner = WeakReference(owner)

    /** The view the SDK put into the app's container for an overlay or pause ad. */
    var hostView: AdHostView? = null

    /** A linear ad's screen once it attached, and the registration that hands it the ad. */
    var linearScreen: LinearAdActivity? = null
    var linearPresentation: LinearAdActivity.Presentation? = null

    /** Decides `viewable` / `notViewable` and reports exposure while the ad is on screen. */
    var meter: ViewabilityMeter? = null

    /** Time seen on screen, for an overlay or pause ad; it also ends an overlay whose duration elapsed. */
    var clock: ActiveTimeClock? = null

    /** The ad is ending: measuring stops and the inline page leaves the screen. Returns the active time seen. */
    fun tearDown(): Long {
        val activeTime = clock?.stop() ?: 0
        // The stopped clock's callback reaches the owner's playback: an ended ad an app keeps must not keep its VideoAds.
        clock?.onActivate = null
        clock = null
        meter?.stop()
        meter = null
        hostView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        hostView = null
        linearPresentation?.let(LinearAdActivity::forget)
        return activeTime
    }

    /** `onAdEnded` is next: the slot is free and the owner stops following the ad. */
    fun release() {
        linearPresentation = null
        linearScreen = null
        if (Sdk.presentation === this) Sdk.presentation = null
        owner.get()?.adDidEnd(ad)
    }

    /** `show` could not put the ad on screen after it committed: the slot is free, the owner holds the ad again. */
    fun cancel(wasHeld: Boolean) {
        if (Sdk.presentation === this) Sdk.presentation = null
        owner.get()?.adDidNotShow(ad, wasHeld)
    }

    companion object {
        /** `show` committed: the ad takes the app's one slot and its owner hears it is showing, before any app code runs. */
        fun begin(ad: VideoAd, owner: VideoAds?): Presentation {
            val presentation = Presentation(ad, owner)
            Sdk.presentation = presentation
            owner?.adDidBeginShowing(ad)
            return presentation
        }
    }
}
