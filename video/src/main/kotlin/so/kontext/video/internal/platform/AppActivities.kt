package so.kontext.video.internal.platform

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * An Activity is one screen of the app. This object follows all of the app's screens and answers whether the
 * app is in front and whether a given screen is started or resumed, and it tells an ad on screen when its
 * screen resumes, stops or is destroyed.
 *
 * `initialize` often runs after the first Activity started (after a consent screen, say), when its
 * start has already gone by: at registration the process's own importance says whether something of the
 * app is on screen, until the first lifecycle event; from then on an Activity started before registration
 * makes itself known at its next pause or resume.
 */
internal object AppActivities : Application.ActivityLifecycleCallbacks {
    /** Started and not stopped. Weak: the SDK never keeps an Activity. */
    private val started = WeakHashMap<Activity, Unit>()
    private val resumed = WeakHashMap<Activity, Boolean>()
    private var registeredWith = WeakReference<Application>(null)

    /** What happened to an Activity, for the ads on screen that follow the Activity holding them. */
    enum class Event { RESUMED, STOPPED, DESTROYED }

    /** Told after the tracker noted the event; an ad adds itself while it is on screen. */
    private val listeners = mutableListOf<(Activity, Event) -> Unit>()

    /** Something of the app was on screen at registration, and no lifecycle event has come since. */
    private var wasVisibleAtRegistration = false

    val isAppInForeground: Boolean get() = started.isNotEmpty() || wasVisibleAtRegistration

    /** Some Activity of the app is resumed, as far as the events since registration tell. */
    val hasResumedActivity: Boolean get() = resumed.containsValue(true)

    fun addListener(listener: (Activity, Event) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Activity, Event) -> Unit) {
        listeners.removeAll { it === listener }
    }

    /** Whether [activity] is resumed, `null` when no event of it has come since registration. */
    fun isResumed(activity: Activity): Boolean? = resumed[activity]

    /**
     * Whether [activity] is started (visible, resumed or not), `null` when no event of it has come since
     * registration. One that paused or resumed is in [resumed], so a stopped one is known to be stopped.
     */
    fun isStarted(activity: Activity): Boolean? = when {
        started.containsKey(activity) -> true
        resumed.containsKey(activity) -> false
        else -> null
    }

    /**
     * Whether [activity] is visible to the viewer: started, resumed or not, and not in picture-in-picture.
     * Started is enough: Android starts a screen from `onStart`, a result or permission callback or
     * `onNewIntent` (all before `onResume`), and it comes in front. Known from the Activity's first event; one
     * not seen yet falls back to the Activity's own lifecycle, else is assumed visible (a linear ad's show
     * timeout still catches a screen that never comes).
     */
    fun isVisibleToViewer(activity: Activity): Boolean {
        if (activity.isInPictureInPictureMode) return false
        return isStarted(activity)
            ?: (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED)
            ?: true
    }

    fun register(application: Application, isProcessVisible: () -> Boolean = ::isProcessVisible) {
        if (registeredWith.get() === application) return
        registeredWith.get()?.unregisterActivityLifecycleCallbacks(this)
        registeredWith = WeakReference(application)
        started.clear()
        resumed.clear()
        wasVisibleAtRegistration = isProcessVisible()
        application.registerActivityLifecycleCallbacks(this)
    }

    /**
     * The process holds a visible Activity: its importance is foreground or visible. Not a foreground service
     * (125, between the two), which runs with nothing on screen.
     */
    private fun isProcessVisible(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }.getOrDefault(false)

    /** Tests only: forgets the application and every Activity, as before `initialize`. */
    fun resetForTesting() {
        registeredWith.get()?.unregisterActivityLifecycleCallbacks(this)
        registeredWith = WeakReference(null)
        started.clear()
        resumed.clear()
        wasVisibleAtRegistration = false
        listeners.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        wasVisibleAtRegistration = false
    }

    override fun onActivityStarted(activity: Activity) {
        wasVisibleAtRegistration = false
        started[activity] = Unit
    }

    override fun onActivityResumed(activity: Activity) {
        wasVisibleAtRegistration = false
        // Resumed is started: one started before registration is counted from here.
        started[activity] = Unit
        resumed[activity] = true
        notify(activity, Event.RESUMED)
    }

    override fun onActivityPaused(activity: Activity) {
        wasVisibleAtRegistration = false
        started[activity] = Unit
        resumed[activity] = false
    }

    override fun onActivityStopped(activity: Activity) {
        wasVisibleAtRegistration = false
        started.remove(activity)
        resumed[activity] = false
        notify(activity, Event.STOPPED)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        started.remove(activity)
        resumed.remove(activity)
        notify(activity, Event.DESTROYED)
    }

    // A copy: a listener ends its ad, which removes it from the list.
    private fun notify(activity: Activity, event: Event) = listeners.toList().forEach { it(activity, event) }
}
