package so.kontext.video.internal.screen

import android.app.Activity
import android.view.View
import so.kontext.video.VideoAd
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.platform.AppActivities
import java.lang.ref.WeakReference

/**
 * Where an ad on screen is shown: the one place where the formats differ in how they are put on screen,
 * measured and taken off.
 *
 * Why: an overlay or pause ad sits inline, in a view the SDK adds to the app's container; a linear ad has a
 * full-screen Activity of its own. Kept in one class per kind of screen ([InlineScreen], [LinearScreen], each in its own file), `VideoAd` and `ShownAd` ask
 * "the screen" and never the format.
 *
 * How: `show` makes the screen as it reserves the slot and hands it to the ad's `ShownAd`; the ad then calls
 * it at fixed moments:
 *
 * ```
 *                  InlineScreen                        LinearScreen
 * show             attach(host): InlineAdView into the LinearAdActivity started; its attach (these
 *                  container                           Callbacks) puts the page in it; the
 *                                                      Activity that started it followed
 * configure(meter) on-screen rule, seen → clock        covered by another Activity
 * reachedScreen()  the clock runs, the content rule    nothing: the screen's own onResume showed it
 *                  starts, the holding Activity
 *                  followed
 * end:
 *   stopFollowing() the Activity's listener removed    the same
 *   tearDown(meter) clock → meter → host view out;     meter stopped, the screen's registration
 *                  returns the active time             forgotten; returns 0
 *                  (overlayViewDuration)
 *   close(onGone)  nothing to wait for: false          the screen finishes; onGone once it is gone: true
 * ```
 *
 * A screen never ends the ad's life itself: it calls `ad.end(...)`, and `onGone` (the ad's `finish`) is the
 * only way it reaches `onAdEnded`.
 */
internal sealed class AdScreen(
    /** The ad this screen shows. */
    val ad: VideoAd,
) {
    /** An overlay's or pause ad's view in the app's container, not a screen of its own. */
    abstract val isInline: Boolean

    /** The view whose first layout with an area counts an image or HTML creative's impression. */
    abstract val impressionTarget: View?

    /** The creative was placed in a frame with an area: the impression may count. */
    abstract val isPlaced: Boolean

    /**
     * Stops waiting for the page's first layout, where an image or HTML creative's impression counts. Inline in
     * practice (a linear ad's creative is a video, whose impression is its first frame); kept on every screen so
     * a linear ad with another creative waits the same way.
     */
    var impressionWait: (() -> Unit)? = null

    /** Follows the Activity the screen depends on while the ad is on screen; removed when the ad ends. */
    private var activityListener: ((Activity, AppActivities.Event) -> Unit)? = null

    /** The meter's inputs and outputs that depend on where the ad is. */
    abstract fun configure(meter: ViewabilityMeter)

    /** The ad reached the screen: what runs while it is on screen starts. */
    abstract fun reachedScreen()

    /**
     * The ad is ending, in this order: its clock freezes for good, [meter] stops, what it put on screen goes.
     * Returns the active time seen on screen.
     */
    abstract fun tearDown(meter: ViewabilityMeter?): Long

    /**
     * Closes what is still up. Returns `true` when the ad must wait: [onGone] then runs once the screen is gone
     * (or after a timeout); `false` when nothing is left to wait for, and [onGone] never runs.
     */
    abstract fun close(onGone: () -> Unit): Boolean

    /** The ad is ending: the Activity it followed is no longer heard. */
    fun stopFollowing() {
        activityListener?.let(AppActivities::removeListener)
        activityListener = null
    }

    /** The ad is ending: the first-layout wait stops, its impression never counted. */
    fun stopWaitingForImpression() {
        impressionWait?.invoke()
        impressionWait = null
    }

    /** Hears [activity]'s events until the ad ends. */
    protected fun follow(activity: Activity, onEvent: (Activity, AppActivities.Event) -> Unit) {
        val target = WeakReference(activity)
        val listener: (Activity, AppActivities.Event) -> Unit = { which, event -> if (which === target.get() && !ad.hasEnded) onEvent(which, event) }
        activityListener?.let(AppActivities::removeListener)
        activityListener = listener
        AppActivities.addListener(listener)
    }
}
