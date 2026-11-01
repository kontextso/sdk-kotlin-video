package so.kontext.video

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.ad.handle
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.wire.AdPayload
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
}
