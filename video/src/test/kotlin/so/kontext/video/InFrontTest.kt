package so.kontext.video

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.support.ActivityTracker
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.wire.AdPayload

/** Whether the app, and the Activity a linear ad starts from, are in front: what the clocks and `show(activity)` read. */
@RunWith(RobolectricTestRunner::class)
class InFrontTest : SdkTest() {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    /** The process's importance as the system reports it to `getMyMemoryState`. */
    private fun processImportance(importance: Int) {
        val activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activityManager).setProcesses(
            listOf(
                ActivityManager.RunningAppProcessInfo().apply {
                    pid = Process.myPid()
                    processName = app.packageName
                    this.importance = importance
                },
            ),
        )
    }

    // region The app in front

    @Test fun anActivityStartedBeforeInitializeKeepsTheAppInFront() {
        ActivityTracker.resetForTesting()
        val screen = Robolectric.buildActivity(Activity::class.java).setup()
        processImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        ActivityTracker.register(app)
        assertTrue("on screen before the SDK listened", Environment.isAppInForeground())
        screen.pause()
        assertTrue("paused is still started", Environment.isAppInForeground())
        screen.stop()
        assertFalse(Environment.isAppInForeground())
        screen.start()
        assertTrue(Environment.isAppInForeground())
    }

    @Test fun anAppInTheBackgroundAtInitializeIsNotInFront() {
        ActivityTracker.resetForTesting()
        processImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED)
        ActivityTracker.register(app)
        assertFalse(Environment.isAppInForeground())
    }

    @Test fun aProcessWithOnlyAForegroundServiceIsNotInFront() {
        ActivityTracker.resetForTesting()
        // A music or download service: foreground for the system, nothing on screen.
        processImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
        ActivityTracker.register(app)
        assertFalse(Environment.isAppInForeground())
        ActivityTracker.resetForTesting()
        processImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        ActivityTracker.register(app)
        assertTrue("an Activity visible behind another app's", Environment.isAppInForeground())
    }

    @Test fun initializeBeforeAnyActivityCountsOnlyTheActivitiesThatStart() {
        ActivityTracker.resetForTesting()
        // Application.onCreate of a launch: the process is already foreground, no Activity exists yet.
        processImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        ActivityTracker.register(app)
        val screen = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(Environment.isAppInForeground())
        screen.pause().stop()
        assertFalse("the one Activity went", Environment.isAppInForeground())
    }

    @Test fun theLoadTimeoutRunsWhenInitializeCameAfterTheFirstActivity() {
        ActivityTracker.resetForTesting()
        Robolectric.buildActivity(Activity::class.java).setup()
        processImportance(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        ActivityTracker.register(app)
        FrameBridge.loadTimeoutMillis = 500
        try {
            val ad = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), Environment.now(), owner())
            var result: Result<Unit>? = null
            Environment.scope.launch { result = runCatching { ad.load() } }
            idle(800)
            assertTrue("${result?.exceptionOrNull()}", result?.exceptionOrNull() is VideoAdException.CreativeFailed)
        } finally {
            FrameBridge.loadTimeoutMillis = 10_000
        }
    }

    // endregion

    // region show(activity)

    private fun assertRefused(ad: VideoAd, from: Activity) {
        try {
            ad.show(from)
            org.junit.Assert.fail("shown")
        } catch (error: VideoAdException) {
            assertTrue("$error", error is VideoAdException.PresenterUnavailable)
        }
        assertTrue("the ad stays ready", ad.isReady)
        assertFalse("nothing spent", ad.wasShown)
        assertNull(Sdk.adOnScreen)
        assertNull("no screen starts", shadowOf(app).nextStartedActivity)
    }

    @Test fun aLinearAdIsRefusedFromAnActivityThatIsNotResumed() {
        val screen = Robolectric.buildActivity(Activity::class.java).setup()
        val ad = linear()
        screen.pause()
        assertRefused(ad, screen.get())
        screen.stop()
        assertRefused(ad, screen.get())
        screen.start().resume()
        ad.show(screen.get())
        assertTrue(ad.wasShown)
    }

    @Test fun aLinearAdIsRefusedFromAnActivityInPictureInPicture() {
        val screen = Robolectric.buildActivity(Activity::class.java).setup().get()
        @Suppress("DEPRECATION")
        screen.enterPictureInPictureMode()
        assertTrue(screen.isInPictureInPictureMode)
        assertRefused(linear(), screen)
    }

    @Test fun anActivityTheTrackerHasNotSeenFallsBackToItsLifecycle() {
        val screen = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        ActivityTracker.resetForTesting() // the SDK listens from now on: it has seen nothing of this Activity
        screen.pause()
        ActivityTracker.resetForTesting()
        assertRefused(linear(), screen.get())
        assertEquals(androidx.lifecycle.Lifecycle.State.STARTED, screen.get().lifecycle.currentState)
    }

    // endregion
}
