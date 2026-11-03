package so.kontext.video

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.ad.handle
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.LinearAdActivity
import so.kontext.video.internal.support.Environment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A player screen that counts `onUserLeaveHint()`, where apps enter picture-in-picture. */
class LeaveHintHostActivity : Activity() {
    @Volatile var leaveHints = 0
    val paused = CountDownLatch(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        leaveHints += 1
    }

    override fun onPause() {
        super.onPause()
        paused.countDown()
    }
}

/** A player screen locked to landscape in the manifest, as a landscape video app's. */
class LandscapeHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}

/** The linear ad's screen on a real system: started by the app, so the host hears no user-leave hint. */
@RunWith(AndroidJUnit4::class)
class LinearLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    private val payload = """
        {"id": "ad-3", "format": "linear", "creative": "video", "trigger": {"kind": "manual"},
         "price": {"value": 12.0, "currency": "USD", "precision": "exact"}, "expires": 600000,
         "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/ad-3"}, "tracking": [],
         "duration": 30000, "skipOffset": 5000}
    """.trimIndent()

    @Test fun theHostHearsNoUserLeaveHintWhenTheAdStarts() {
        main {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { _, _ -> }
            Sdk.configureForTesting(Configuration("test-token"))
        }
        val host = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, LeaveHintHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as LeaveHintHostActivity
        val ended = CountDownLatch(1)
        var ad: VideoAd? = null
        try {
            main {
                val owner = VideoAds(Content("ep", "en"))
                val parsed = AdPayload.parse(Json.parseToJsonElement(payload))
                val linear = VideoAd(parsed, Environment.now(), owner)
                val bridge = FrameBridge(Environment.context!!, parsed.id, parsed.frameUrl, true)
                bridge.receive("""{"adId":"ad-3","type":"ad.loaded"}""", FrameBridge.Origin.of(parsed.frameUrl), true)
                bridge.onEvent = { linear.handle(it) }
                linear.loadedBridge = bridge
                linear.isLoaded = true
                linear.listener = object : VideoAdListener {
                    override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) = ended.countDown()
                }
                ad = linear
                linear.show(host)
            }
            assertTrue("the ad's screen covered the host", host.paused.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            assertEquals("an app-started screen is not the user leaving", 0, host.leaveHints)
        } finally {
            main { ad?.end(VideoAd.EndReason.INTERRUPTED) }
            ended.await(5, TimeUnit.SECONDS)
            main {
                host.finish()
                Sdk.resetForTesting()
                Environment.reset()
            }
        }
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).reader().readText().trim() }

    private fun linearAd(): VideoAd {
        val owner = VideoAds(Content("ep", "en"))
        val parsed = AdPayload.parse(Json.parseToJsonElement(payload))
        val linear = VideoAd(parsed, Environment.now(), owner)
        val bridge = FrameBridge(Environment.context!!, parsed.id, parsed.frameUrl, true)
        bridge.receive("""{"adId":"ad-3","type":"ad.loaded"}""", FrameBridge.Origin.of(parsed.frameUrl), true)
        bridge.onEvent = { linear.handle(it) }
        linear.loadedBridge = bridge
        linear.isLoaded = true
        return linear
    }

    private fun resumedLinearScreen(): LinearAdActivity? {
        var found: LinearAdActivity? = null
        main { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<LinearAdActivity>().firstOrNull() }
        return found
    }

    @Test fun aLandscapeAppGetsItsLinearAdInLandscapeWithAutoRotateOff() {
        val autoRotate = shell("settings get system accelerometer_rotation")
        val userRotation = shell("settings get system user_rotation")
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation 0") // the device held in portrait
        main {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { _, _ -> }
            Sdk.configureForTesting(Configuration("test-token"))
        }
        val host = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, LandscapeHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val ended = CountDownLatch(1)
        var ad: VideoAd? = null
        try {
            instrumentation.waitForIdleSync()
            main {
                ad = linearAd().also {
                    it.listener = object : VideoAdListener {
                        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) = ended.countDown()
                    }
                    it.show(host)
                }
            }
            val deadline = System.currentTimeMillis() + 10_000
            var screen = resumedLinearScreen()
            while (screen == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                screen = resumedLinearScreen()
            }
            assertTrue("the ad's screen came up", screen != null)
            instrumentation.waitForIdleSync()
            Thread.sleep(500) // a rotation would show by now
            instrumentation.waitForIdleSync()
            var orientation = 0
            var wide = false
            main {
                orientation = screen!!.resources.configuration.orientation
                wide = screen.window.decorView.width > screen.window.decorView.height
            }
            assertEquals("the ad follows the app's landscape screen", android.content.res.Configuration.ORIENTATION_LANDSCAPE, orientation)
            assertTrue("laid out wide", wide)
        } finally {
            main { ad?.end(VideoAd.EndReason.INTERRUPTED) }
            ended.await(5, TimeUnit.SECONDS)
            main {
                host.finish()
                Sdk.resetForTesting()
                Environment.reset()
            }
            shell("settings put system accelerometer_rotation $autoRotate")
            shell("settings put system user_rotation $userRotation")
        }
    }

    @Test fun theActivityThatStartedALinearAdFinishingEndsItInterruptedAndClosesItsScreen() {
        main {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { _, _ -> }
            Sdk.configureForTesting(Configuration("test-token"))
        }
        val host = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, LeaveHintHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as LeaveHintHostActivity
        val ended = CountDownLatch(1)
        var ad: VideoAd? = null
        try {
            main {
                ad = linearAd().also {
                    it.listener = object : VideoAdListener {
                        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                            endReason = reason
                            ended.countDown()
                        }
                    }
                    it.show(host)
                }
            }
            assertTrue("the ad's screen covered the host", host.paused.await(10, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            main { host.finish() } // the app closes the screen that started the ad
            assertTrue("the ad ended", ended.await(10, TimeUnit.SECONDS))
            assertEquals(VideoAd.EndReason.INTERRUPTED, endReason)
            instrumentation.waitForIdleSync()
            assertEquals("its screen closed", null, resumedLinearScreen())
        } finally {
            main {
                if (ad?.hasEnded == false) ad?.end(VideoAd.EndReason.INTERRUPTED)
                Sdk.resetForTesting()
                Environment.reset()
            }
        }
    }

    @Test fun aTranslucentActivityOverTheLinearAdCoversItAndADialogDoesNot() {
        main {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { _, _ -> }
            Sdk.configureForTesting(Configuration("test-token"))
        }
        val host = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, LeaveHintHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as LeaveHintHostActivity
        val ended = CountDownLatch(1)
        var ad: VideoAd? = null
        var dialog: android.app.AlertDialog? = null
        try {
            main {
                ad = linearAd().also {
                    it.listener = object : VideoAdListener {
                        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) = ended.countDown()
                    }
                    it.show(host)
                }
            }
            val screen = waitFor { resumedLinearScreen() }
            assertTrue("the ad's screen came up", screen != null)
            var covered = true
            main { covered = screen!!.isCovered }
            assertEquals("resumed, nothing over it", false, covered)
            // A dialog over the ad: the screen stays resumed, the viewer sees the ad around it.
            main { dialog = android.app.AlertDialog.Builder(screen).setMessage("a question").show() }
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            main { covered = screen!!.isCovered }
            assertEquals("a dialog does not cover", false, covered)
            main { dialog?.dismiss() }
            // A translucent Activity over it: the screen pauses and stays drawn; the meter reads it as covered.
            var exposure = -1.0
            main { ad!!.loadedBridge!!.onSend = { type, message -> if (type == "ad.exposure") exposure = message["exposedPercentage"].toString().toDouble() } }
            main { screen!!.startActivity(Intent(screen, TranslucentCoverActivity::class.java)) }
            val cover = waitFor {
                var found: Activity? = null
                main { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<TranslucentCoverActivity>().firstOrNull() }
                found
            }
            assertTrue("the cover came up", cover != null)
            instrumentation.waitForIdleSync()
            main {
                covered = screen!!.isCovered
                ad!!.meter!!.tick()
            }
            assertEquals("a translucent Activity covers", true, covered)
            assertEquals("the meter reads 0", 0.0, exposure, 0.0)
            main { cover!!.finish() }
            assertTrue("the ad's screen came back", waitFor { resumedLinearScreen() } != null)
            main { covered = screen!!.isCovered }
            assertEquals("uncovered once the cover went", false, covered)
        } finally {
            main {
                dialog?.dismiss()
                ad?.end(VideoAd.EndReason.INTERRUPTED)
            }
            ended.await(5, TimeUnit.SECONDS)
            main {
                host.finish()
                Sdk.resetForTesting()
                Environment.reset()
            }
        }
    }

    private fun <T> waitFor(find: () -> T?): T? {
        val deadline = System.currentTimeMillis() + 10_000
        var found = find()
        while (found == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            found = find()
        }
        return found
    }

    @Volatile private var endReason: VideoAd.EndReason? = null
}
