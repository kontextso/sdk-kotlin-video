package so.kontext.video

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.internal.core.Sdk
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** `show(container)` on real views in a real window: what is off screen or refuses the ad is not shown. */
@RunWith(AndroidJUnit4::class)
class InlineShowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)

    private val payload = """
        {"id": "ad-2", "format": "pause", "creative": "image", "trigger": {"kind": "manual"},
         "price": {"value": 5.5, "currency": "EUR", "precision": "estimated"}, "expires": 600000,
         "rendering": {"kind": "frame", "url": "https://frames.test/v2/frame/ad-2"}, "tracking": [],
         "position": "fullscreen"}
    """.trimIndent()

    private fun ad(owner: VideoAds): VideoAd {
        val parsed = AdPayload.parse(Json.parseToJsonElement(payload))
        val ad = VideoAd(parsed, Environment.now(), owner)
        val bridge = FrameBridge(Environment.context!!, parsed.id, parsed.frameUrl, false)
        bridge.receive("""{"adId":"ad-2","type":"ad.loaded"}""", Origin.of(parsed.frameUrl), true)
        bridge.onEvent = { ad.handle(it) }
        ad.loadedBridge = bridge
        ad.state.markLoaded()
        return ad
    }

    private fun refused(ad: VideoAd, container: ViewGroup): Boolean = try {
        ad.show(container)
        false
    } catch (_: VideoAdException.CannotShowNow) {
        true
    }

    @Test fun aNeighbourPageAndAScrollViewWithItsChildAreRefusedAndAVisibleContainerShows() {
        main {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { _, _ -> }
            Sdk.configureForTesting(Configuration("test-token"))
        }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, TestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as TestHostActivity
        lateinit var root: FrameLayout
        lateinit var neighbour: FrameLayout
        lateinit var scroll: ScrollView
        lateinit var visible: FrameLayout
        main {
            root = FrameLayout(activity)
            val match = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            neighbour = FrameLayout(activity)
            scroll = ScrollView(activity).apply { addView(View(activity)) }
            visible = FrameLayout(activity)
            root.addView(visible, match)
            root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(neighbour, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            activity.setContentView(root)
        }
        instrumentation.waitForIdleSync()
        try {
            main {
                // A pager's next page: laid out at full size, one screen to the right.
                neighbour.translationX = root.width.toFloat()
                val owner = VideoAds(Content("ep", "en"))
                val ad = ad(owner)
                assertTrue("the neighbour page", refused(ad, neighbour))
                assertTrue("a ScrollView that has its child", refused(ad, scroll))
                assertTrue(ad.isReady && !ad.wasShown)
                assertNull(ScreenSlot.current)
                ad.show(visible)
                assertTrue(ad.wasShown)
                owner.destroy()
            }
        } finally {
            main {
                activity.finish()
                Sdk.resetForTesting()
                Environment.reset()
            }
        }
    }

    @Test fun aFullScreenPauseAdKeepsItsPageClearOfTheSystemBars() {
        main {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { _, _ -> }
            Sdk.configureForTesting(Configuration("test-token"))
        }
        // An edge-to-edge player, as a video app's, whose container reaches under the bars (enforced from API
        // 35), and one the system keeps clear of them: in both, the page is the container less the bars.
        try {
            for (edgeToEdge in listOf(true, false)) fullScreenPauseClearsTheBars(edgeToEdge)
        } finally {
            main {
                Sdk.resetForTesting()
                Environment.reset()
            }
        }
    }

    private fun fullScreenPauseClearsTheBars(edgeToEdge: Boolean) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, TestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as TestHostActivity
        lateinit var player: FrameLayout
        main {
            WindowCompat.setDecorFitsSystemWindows(activity.window, !edgeToEdge)
            player = FrameLayout(activity)
            activity.setContentView(player)
        }
        instrumentation.waitForIdleSync()
        try {
            lateinit var owner: VideoAds
            lateinit var ad: VideoAd
            main {
                owner = VideoAds(Content("ep", "en"))
                ad = ad(owner)
                ad.show(player)
            }
            instrumentation.waitForIdleSync()
            main {
                val mode = if (edgeToEdge) "edge to edge" else "fitting the bars"
                val root = player.rootView
                val bars = ViewCompat.getRootWindowInsets(player)!!
                    .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                assertTrue("the emulator shows a status bar", bars.top > 0)
                // In window coordinates: the part of the window the bars and the cutout leave free.
                val clear = Rect(bars.left, bars.top, root.width - bars.right, root.height - bars.bottom)
                val container = windowRect(player)
                val host = player.getChildAt(0)
                val page = windowRect(ad.loadedBridge!!.webView)
                assertEquals("$mode: the host covers the whole container", container, windowRect(host))
                assertEquals("$mode: opaque behind the bars", 255, (host.background as ColorDrawable).alpha)
                assertTrue("$mode: the page $page stays clear of the bars $clear", clear.contains(page))
                val expected = Rect(container).apply { intersect(clear) }
                assertEquals("$mode: the page is the container less the bars", expected, page)
                owner.destroy()
            }
        } finally {
            main { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun windowRect(view: View): Rect {
        val location = IntArray(2).also(view::getLocationInWindow)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    @Test fun anotherScreenOfTheAppOverTheAdEndsItInterrupted() {
        main {
            Environment.context = instrumentation.targetContext
            Environment.sendBeacon = { _, _ -> }
            Sdk.configureForTesting(Configuration("test-token"))
        }
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, TestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as TestHostActivity
        lateinit var player: FrameLayout
        main {
            player = FrameLayout(activity)
            activity.setContentView(player)
        }
        instrumentation.waitForIdleSync()
        val ended = CountDownLatch(1)
        var cover: Activity? = null
        lateinit var owner: VideoAds
        try {
            main {
                owner = VideoAds(Content("ep", "en"))
                ad(owner).apply {
                    listener = object : VideoAdListener {
                        override fun onAdEnded(ad: VideoAd, reason: VideoAd.EndReason) {
                            endReasons += reason
                            ended.countDown()
                        }
                    }
                    show(player)
                }
            }
            instrumentation.waitForIdleSync()
            // The app opens a screen of its own over the player, as a sign-in or an episode list would.
            val monitor = instrumentation.addMonitor(CoverActivity::class.java.name, null, false)
            main { activity.startActivity(Intent(activity, CoverActivity::class.java)) }
            cover = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertTrue("the ad ended", ended.await(10, TimeUnit.SECONDS))
            assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), endReasons.toList())
        } finally {
            main {
                owner.destroy()
                cover?.finish()
                activity.finish()
                Sdk.resetForTesting()
                Environment.reset()
            }
        }
    }

    private val endReasons: MutableList<VideoAd.EndReason> = Collections.synchronizedList(mutableListOf())
}
