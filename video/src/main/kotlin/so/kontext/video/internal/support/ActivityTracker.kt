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

    /** Something of the app was on screen at registration, and no lifecycle event has come since. */
    private var visibleAtRegistration = false

    val isAppInForeground: Boolean get() = started.isNotEmpty() || visibleAtRegistration

    /** Whether [activity] is resumed, `null` when no event of it has come since registration. */
    fun isResumed(activity: Activity): Boolean? = resumed[activity]

    fun register(application: Application, isProcessVisible: () -> Boolean = ::isProcessVisible) {
        if (registeredWith.get() === application) return
        registeredWith.get()?.unregisterActivityLifecycleCallbacks(this)
        registeredWith = WeakReference(application)
        started.clear()
        resumed.clear()
        visibleAtRegistration = isProcessVisible()
        application.registerActivityLifecycleCallbacks(this)
    }

    /** The process holds a visible Activity: its importance is foreground or visible. */
    private fun isProcessVisible(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }.getOrDefault(false)

    /** Tests only: forgets the application and every Activity, as before `initialize`. */
    fun resetForTesting() {
        registeredWith.get()?.unregisterActivityLifecycleCallbacks(this)
        registeredWith = WeakReference(null)
        started.clear()
        resumed.clear()
        visibleAtRegistration = false
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
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        started.remove(activity)
        resumed.remove(activity)
    }
}
