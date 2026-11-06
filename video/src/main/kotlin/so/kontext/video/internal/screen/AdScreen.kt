package so.kontext.video.internal.screen

import android.app.Activity
import android.view.View
import so.kontext.video.VideoAd
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.measurement.ViewExposure
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.platform.AppActivities
import java.lang.ref.WeakReference

/**
 * Where an ad on screen is shown: the one place where the formats differ in how they are put on screen,
 * measured and taken off.
 *
 * The words, as everywhere in the SDK: a view is an Android view (`InlineAdView`, the app's container); an
 * Activity is an Android screen; a screen (this class) is where an ad is shown, inline in the player
 * ([InlineScreen]) or full screen ([LinearScreen]); on screen is visible to the viewer.
 *
 * Why: an overlay or pause ad sits inline, in a view the SDK adds to the app's container; a linear ad has a
 * full-screen Activity of its own. Kept in one class per kind of screen, each in its own file, `VideoAd` asks
 * "the screen" and never the format.
 *
 * How: `show` makes the screen as it reserves the slot (`ScreenSlot`) and keeps it as the ad's `screen`, which
 * also holds the viewability meter; the ad then calls it at fixed moments:
 *
 * ```
 *                  InlineScreen                        LinearScreen
 * show             prepare (checks, variant), then      start: LinearAdActivity started; its
 *                  addHost: InlineAdView into the      attach (these Callbacks) puts the page in
 *                  container                           it; the Activity that started it followed
 * configure()      on-screen rule, seen → clock        covered by another Activity, or by its own
 *                                                      in-app browser
 * reachedScreen()  the clock runs, the content rule    nothing: the Activity's own onResume showed it
 *                  starts, the holding Activity
 *                  followed
 * end:
 *   stopFollowing() the Activity's listener removed,   the same
 *                  the in-app browser's too
 *   tearDown()      clock → meter → host view out;     meter stopped, the Activity's registration
 *                  returns the active time             forgotten; returns 0
 *                  (overlayViewDuration)
 *   close(onGone)  nothing to wait for: false          the Activity finishes; onGone once gone: true
 * ```
 *
 * A click's web link opens in the screen's [inAppBrowser], over [showingActivity]: a Custom Tab, which stops
 * that Activity while the app stays in front. Inline, that stop is not another screen of the app covering the ad
 * (`InlineScreen.followHoldingActivity`); linear, it covers the ad's Activity as any other does.
 *
 * A screen never ends the ad's life itself: it calls `ad.end(...)`, and `onGone` (the ad's `finish`) is the
 * only way it reaches `onAdEnded`.
 */
internal sealed class AdScreen(
    /** The ad this screen shows. */
    val ad: VideoAd,
) {
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

    /** Decides `viewable` / `notViewable` and reports exposure while the ad is on screen; set when it reaches it. */
    var meter: ViewabilityMeter? = null

    /** The Activity the ad is on screen in: the one holding the container, or a linear ad's own. */
    abstract val showingActivity: Activity?

    /** The in-app browser the ad's clicks open web links in, over [showingActivity]. */
    val inAppBrowser = InAppBrowser { showingActivity }

    /** Follows the Activity the screen depends on while the ad is on screen; removed when the ad ends. */
    private var activityListener: ((Activity, AppActivities.Event) -> Unit)? = null

    /** The [meter]'s inputs and outputs that depend on where the ad is. */
    abstract fun configure()

    /** The ad reached the screen: what runs while it is on screen starts. */
    abstract fun reachedScreen()

    /**
     * The ad is ending, in this order: its clock freezes for good, [meter] stops (and goes), what it put on
     * screen goes. Returns the active time seen on screen.
     */
    abstract fun tearDown(): Long

    /**
     * Closes what is still up. Returns `true` when the ad must wait: [onGone] then runs once its Activity is gone
     * (or after a timeout); `false` when nothing is left to wait for, and [onGone] never runs.
     */
    abstract fun close(onGone: () -> Unit): Boolean

    /** The ad is ending: the Activity it followed, and its in-app browser's return, are no longer heard. */
    fun stopFollowing() {
        activityListener?.let(AppActivities::removeListener)
        activityListener = null
        inAppBrowser.stop()
    }

    /**
     * An image or HTML creative's impression: [onImpression] runs at the page's first layout with an area.
     * Until Android has laid the page out in a window with an area, nothing of it was drawn: a container that
     * gives the ad no room ends it (`INTERRUPTED`) with no impression. So does an inline page laid out wholly off
     * screen where it was placed: a feed scrolled so the player's middle shows, not its top card.
     */
    fun awaitImpression(onImpression: () -> Unit) {
        // Inline, the host: it has an area only where the container gives the ad room, and places the page.
        val view = impressionTarget ?: ad.loadedBridge?.webView ?: return
        val isInline = this is InlineScreen
        impressionWait = view.awaitLayout(isReady = { isPlaced }) {
            impressionWait = null
            val page = ad.loadedBridge?.webView
            if (isInline && page != null && page.width > 0 && page.height > 0 && ViewExposure.measure(page).share <= 0.0) {
                Logger.warning { "ad ${ad.id.take(8)}: nothing of the ad is on screen where it was placed; ended as interrupted" }
                ad.end(VideoAd.EndReason.INTERRUPTED)
                return@awaitLayout
            }
            onImpression()
        }
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
