package so.kontext.video.internal

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The SDK's seams: what tests replace and production never touches. Every value has its production
 * default; [reset] restores them all.
 */
internal object Environment {
    /** The application context, set by `KontextVideo.initialize`. */
    var context: Context? = null
        set(value) {
            field = value
            (value as? Application)?.let(ActivityTracker::register)
        }

    /** Whether an Activity of the app is started: the clocks that count only time in front read it. */
    var isAppInForeground: () -> Boolean = { ActivityTracker.startedCount > 0 }

    /** Where the SDK's own work runs: the main thread, never blocking it. */
    var scope: CoroutineScope = newScope()

    /**
     * The clock an ad's lifetime is measured on, in milliseconds: monotonic and still counting while the
     * device sleeps (an ad must expire in a locked pocket too). Never wall time: the user can set that one.
     */
    var now: () -> Long = { SystemClock.elapsedRealtime() }

    /** A programmer error: stops a debuggable app, only logs in a release build. Tests record it instead. */
    var assertionFailure: (String) -> Unit = ::failInDebuggableApp

    /** The browser's user agent, `navigator.userAgent` of a WebView, as KontextKit reads and caches it. */
    var userAgent: suspend () -> String? = { context?.let { DeviceFacts.userAgent(it) } }

    /** The advertising id when the viewer allows it, else `null`. */
    var advertisingId: suspend () -> String? = { context?.let { DeviceFacts.advertisingId(it) } }

    fun reset() {
        scope = newScope()
        now = { SystemClock.elapsedRealtime() }
        assertionFailure = ::failInDebuggableApp
        userAgent = { context?.let { DeviceFacts.userAgent(it) } }
        advertisingId = { context?.let { DeviceFacts.advertisingId(it) } }
        isAppInForeground = { ActivityTracker.startedCount > 0 }
    }

    private fun failInDebuggableApp(message: String) {
        val debuggable = context?.applicationInfo?.let { it.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 } ?: false
        check(!debuggable) { message }
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}

/** Counts the app's started Activities, so the SDK knows whether the app is in front. */
internal object ActivityTracker : Application.ActivityLifecycleCallbacks {
    var startedCount = 0
        private set
    private var registered = false

    fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedCount += 1
    }

    override fun onActivityStopped(activity: Activity) {
        startedCount = maxOf(0, startedCount - 1)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
