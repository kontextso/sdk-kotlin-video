package so.kontext.video

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
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
import so.kontext.video.internal.rendering.LinearAdActivity
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

    /** The ad was accepted and its screen started from [from]. */
    private fun assertStarted(ad: VideoAd, from: Activity, moment: String) {
        assertTrue("$moment: shown", ad.wasShown)
        val intent = shadowOf(from).nextStartedActivity
        assertEquals(moment, LinearAdActivity::class.java.name, intent?.component?.className)
        ad.end(VideoAd.EndReason.INTERRUPTED) // frees the slot for the next moment
    }

    /** A screen that starts the ad from the callbacks Android runs while it is visible but not yet resumed. */
    class Host : Activity() {
        var ad: VideoAd? = null
        val results = mutableMapOf<String, String>()

        private fun showHere(moment: String) {
            val ad = ad ?: return
            this.ad = null
            results[moment] = try {
                ad.show(this)
                "shown"
            } catch (error: VideoAdException) {
                error.javaClass.simpleName
            }
        }

        override fun onStart() {
            super.onStart()
            showHere("onStart")
        }

        @Deprecated("Deprecated in Java")
        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
            showHere("onActivityResult")
        }

        /** As the framework delivers a result: between onStart and onResume. */
        fun deliverResult() {
            @Suppress("DEPRECATION")
            onActivityResult(1, RESULT_OK, null)
        }

        override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            showHere("onRequestPermissionsResult")
        }

        override fun onNewIntent(intent: Intent) {
            super.onNewIntent(intent)
            showHere("onNewIntent")
        }
    }

    @Test fun aLinearAdStartsFromAVisibleActivityThatIsNotResumedYet() {
        val screen = Robolectric.buildActivity(Host::class.java).setup()
        val host = screen.get()
        // Back from another screen: onStart, then the result callbacks, run before onResume.
        screen.pause().stop()
        host.ad = linear()
        val inStart = host.ad!!
        screen.restart().start()
        assertEquals("shown", host.results["onStart"])
        assertStarted(inStart, host, "onStart")
        for (moment in listOf("onActivityResult", "onRequestPermissionsResult")) {
            val ad = linear()
            host.ad = ad
            if (moment == "onActivityResult") {
                host.deliverResult()
            } else {
                host.onRequestPermissionsResult(1, arrayOf("android.permission.CAMERA"), intArrayOf(0))
            }
            assertEquals(moment, "shown", host.results[moment])
            assertStarted(ad, host, moment)
        }
        // A singleTop player gets the next episode in onNewIntent, delivered paused.
        screen.resume().pause()
        val ad = linear()
        host.ad = ad
        screen.newIntent(Intent())
        assertEquals("shown", host.results["onNewIntent"])
        assertStarted(ad, host, "onNewIntent")
    }

    @Test fun aLinearAdIsRefusedFromAStoppedOrFinishedActivity() {
        val screen = Robolectric.buildActivity(Activity::class.java).setup()
        val ad = linear()
        screen.pause().stop()
        assertRefused(ad, screen.get())
        screen.restart().start().resume()
        screen.get().finish()
        assertRefused(ad, screen.get())
        val destroyed = Robolectric.buildActivity(Activity::class.java).setup().pause().stop().destroy().get()
        assertRefused(ad, destroyed)
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
        screen.pause().stop()
        ActivityTracker.resetForTesting()
        assertEquals(androidx.lifecycle.Lifecycle.State.CREATED, screen.get().lifecycle.currentState)
        assertRefused(linear(), screen.get())
        val started = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup().pause()
        ActivityTracker.resetForTesting()
        assertEquals(androidx.lifecycle.Lifecycle.State.STARTED, started.get().lifecycle.currentState)
        val ad = linear()
        ad.show(started.get())
        assertStarted(ad, started.get(), "a started Activity the tracker has not seen")
    }

    // endregion
}
