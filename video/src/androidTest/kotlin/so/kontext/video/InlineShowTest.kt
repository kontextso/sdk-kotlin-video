package so.kontext.video

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.ad.handle
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.wire.AdPayload

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
        bridge.receive("""{"adId":"ad-2","type":"ad.loaded"}""", FrameBridge.Origin.of(parsed.frameUrl), true)
        bridge.onEvent = { ad.handle(it) }
        ad.loadedBridge = bridge
        ad.isLoaded = true
        return ad
    }

    private fun refused(ad: VideoAd, container: ViewGroup): Boolean = try {
        ad.show(container)
        false
    } catch (_: VideoAdException.PresenterUnavailable) {
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
                assertNull(Sdk.adOnScreen)
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
}
