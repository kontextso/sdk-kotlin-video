package so.kontext.video.internal.support

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Follows the app's Activities, so the SDK knows whether the app is in front and whether an Activity is
 * resumed. `initialize` often runs after the first Activity started (after a consent screen, say), when its
 * start has already gone by: at registration the process's own importance says whether something of the
 * app is on screen, until the first lifecycle event; from then on an Activity started before registration
 * makes itself known at its next pause or resume.
 */
internal object ActivityTracker : Application.ActivityLifecycleCallbacks {
    /** Started and not stopped. Weak: the SDK never keeps an Activity. */
    private val started = WeakHashMap<Activity, Unit>()
    private val resumed = WeakHashMap<Activity, Boolean>()
    private var registeredWith = WeakReference<Application>(null)

    /** What happened to an Activity, for the ads on screen that follow the Activity holding them. */
    enum class Event { STOPPED, DESTROYED }

    /** Told after the tracker noted the event; an ad adds itself while it is on screen. */
    private val listeners = mutableListOf<(Activity, Event) -> Unit>()

    fun addListener(listener: (Activity, Event) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Activity, Event) -> Unit) {
        listeners.removeAll { it === listener }
    }

    /** Something of the app was on screen at registration, and no lifecycle event has come since. */
    private var visibleAtRegistration = false

    val isAppInForeground: Boolean get() = started.isNotEmpty() || visibleAtRegistration

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

    fun register(application: Application, isProcessVisible: () -> Boolean = ::isProcessVisible) {
        if (registeredWith.get() === application) return
        registeredWith.get()?.unregisterActivityLifecycleCallbacks(this)
        registeredWith = WeakReference(application)
        started.clear()
        resumed.clear()
        visibleAtRegistration = isProcessVisible()
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
        visibleAtRegistration = false
        listeners.clear()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        visibleAtRegistration = false
    }

    override fun onActivityStarted(activity: Activity) {
        visibleAtRegistration = false
        started[activity] = Unit
    }

    override fun onActivityResumed(activity: Activity) {
        visibleAtRegistration = false
        // Resumed is started: one started before registration is counted from here.
        started[activity] = Unit
        resumed[activity] = true
    }

    override fun onActivityPaused(activity: Activity) {
        visibleAtRegistration = false
        started[activity] = Unit
        resumed[activity] = false
    }

    override fun onActivityStopped(activity: Activity) {
        visibleAtRegistration = false
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
