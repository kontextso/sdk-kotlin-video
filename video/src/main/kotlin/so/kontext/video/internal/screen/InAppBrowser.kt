package so.kontext.video.internal.screen

import android.app.Activity
import android.content.Intent
import so.kontext.kit.ui.InAppBrowserManager
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.platform.AppActivities
import java.lang.ref.WeakReference

/**
 * The in-app browser one ad's click opened over the app: a Custom Tab over the Activity the ad is shown in, and
 * whether it is still up.
 *
 * Why: a web link opened in the default browser sends the app to the background, and the ad freezes there;
 * opened here, the viewer stays in the app and the ad is only covered. Its own browser must not end it the way
 * another screen of the app does, and while the tab is up the ad is not seen: the Activity under it stops, its
 * window is hidden, and the clocks and the viewability wait for the viewer's return.
 *
 * ```
 * click ─ open: tab over the Activity ─ isOpen ─ Back, or the tab closed ─ the Activity resumes: closed
 *            inline: the stop is not a cover (InlineScreen); the window hidden: clocks and viewability frozen
 *            linear: covered (LinearScreen): exposure 0, the page pauses its video, and plays it on again
 * ```
 *
 * One per `AdScreen`, opened by `LinkOpener` for a web link no app claimed; the screens ask [isOpen]. A Custom
 * Tab is the browser's own Activity, so the SDK hears no close: the return of the Activity it opened over is
 * the close. Launched by KontextKit's `InAppBrowserManager`, after the check that a Custom Tabs browser is
 * installed: without one the launch would open the default browser outside the app, which is not this.
 */
internal class InAppBrowser(
    /** The Activity the ad is on screen in, which the tab opens over. */
    private val activity: () -> Activity?,
) {
    /** The tab is up over the ad's Activity: opened, and that Activity has not resumed since. */
    var isOpen = false
        private set

    /** Hears the return of the Activity the tab opened over; set while [isOpen]. */
    private var listener: ((Activity, AppActivities.Event) -> Unit)? = null

    /**
     * Opens [url], a web link, in a Custom Tab over the ad's Activity. `false` when it cannot (no Activity, no
     * Custom Tabs browser, a launch the system refused); the caller opens the link another way.
     */
    fun open(url: String): Boolean {
        val activity = activity()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return false
        if (!hasCustomTabsBrowser(activity)) {
            Logger.info { "no Custom Tabs browser is installed; $url opens outside the app" }
            return false
        }
        InAppBrowserManager.open(activity, url).onFailure { error ->
            Logger.warning { "the in-app browser could not open $url (${error.javaClass.simpleName}); it opens outside the app" }
            return false
        }
        isOpen = true
        follow(activity)
        return true
    }

    /** The screen is going: the Activity's return is no longer heard. */
    fun stop() {
        listener?.let(AppActivities::removeListener)
        listener = null
        isOpen = false
    }

    /** The Activity the tab opened over resumes (the viewer came back) or is destroyed: the tab is no longer over it. */
    private fun follow(opener: Activity) {
        listener?.let(AppActivities::removeListener)
        // Weak: the SDK never keeps an Activity.
        val target = WeakReference(opener)
        val listener: (Activity, AppActivities.Event) -> Unit = { which, event ->
            if (which === target.get() && event != AppActivities.Event.STOPPED) stop()
        }
        this.listener = listener
        AppActivities.addListener(listener)
    }

    private fun hasCustomTabsBrowser(activity: Activity): Boolean = runCatching {
        activity.packageManager.queryIntentServices(Intent(CUSTOM_TABS_SERVICE), 0).isNotEmpty()
    }.getOrDefault(false)

    companion object {
        /** The service a Custom Tabs browser declares; the SDK's manifest queries it (package visibility, Android 11+). */
        const val CUSTOM_TABS_SERVICE = "android.support.customtabs.action.CustomTabsService"
    }
}
