package so.kontext.video.internal.rendering

import so.kontext.video.VideoAd
import so.kontext.video.VideoAds
import so.kontext.video.internal.core.Sdk
import java.lang.ref.WeakReference

/**
 * One ad on screen, from `show` to `onAdEnded`: the app's one slot, what is on screen (an overlay's or pause
 * ad's host view, or a linear ad's screen: [AdScreen]) and what measures it (the viewability meter; the clock is
 * the inline screen's). Made when
 * `show` commits, released when `onAdEnded` is delivered; the slot is taken and freed here and nowhere else,
 * and `Sdk.adOnScreen` and the owner's showing ad follow it.
 */
internal class Presentation private constructor(
    /** Retained from `show` to `onAdEnded`, whatever the app does with its own reference. */
    val ad: VideoAd,
    owner: VideoAds?,
) {
    private val owner = WeakReference(owner)

    /** Where the ad is shown: inline in the app's container, or its own screen. Set by `show` once it made it. */
    var screen: AdScreen? = null

    /** Decides `viewable` / `notViewable` and reports exposure while the ad is on screen. */
    var meter: ViewabilityMeter? = null

    /** The view the SDK put into the app's container for an overlay or pause ad. */
    val hostView: AdHostView? get() = (screen as? AdScreen.InlineScreen)?.hostView

    /** Time seen on screen, for an overlay or pause ad; it also ends an overlay whose duration elapsed. */
    val clock: ActiveTimeClock? get() = (screen as? AdScreen.InlineScreen)?.clock

    /** The registration that hands a linear ad to its screen. */
    val linearCallbacks: LinearAdActivity.Callbacks? get() = screen as? AdScreen.LinearScreen

    /** The ad is ending: measuring stops and the inline page leaves the screen. Returns the active time seen. */
    fun tearDown(): Long {
        val activeTime = screen?.stopClock() ?: 0
        meter?.stop()
        meter = null
        screen?.tearDown()
        return activeTime
    }

    /** `onAdEnded` is next: the slot is free and the owner stops following the ad. */
    fun release() {
        screen = null
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
