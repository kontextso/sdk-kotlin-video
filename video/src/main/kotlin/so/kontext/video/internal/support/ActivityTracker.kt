package so.kontext.video.internal.support

import android.app.Activity
import android.app.Application
import android.os.Bundle

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
