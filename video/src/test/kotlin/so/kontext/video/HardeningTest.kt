package so.kontext.video

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.webkit.WebMessageCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.frame.Origin
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot

/** What a hostile page, a refusing system or an app that keeps its ads must not break. */
@RunWith(RobolectricTestRunner::class)
class HardeningTest : SdkTest() {
    // region A message that is not text

    @Test fun aBinaryMessageIsDroppedInEveryStateAndCountsAsOneBrokenEnvelope() {
        val bridge = FrameBridge(Environment.context!!, "ad-1", "https://frames.test/v2/frame/ad-1", isVideo = false)
        val events = mutableListOf<FrameEvent>()
        bridge.onEvent = { events += it }
        val page = Origin.of("https://frames.test")
        val binary = WebMessageCompat(byteArrayOf(1, 2, 3))
        bridge.receive(binary, Origin.of("https://elsewhere.test"), true)
        bridge.receive(binary, page, false)
        assertTrue("from another sender or frame: dropped, not reported", ErrorReporter.bufferedCodes.isEmpty())
        bridge.receive(binary, page, true) // loading
        bridge.receive("""{"adId":"ad-1","type":"ad.loaded"}""", page, true)
        bridge.show()
        bridge.receive(binary, page, true) // shown
        bridge.receive(WebMessageCompat("""{"adId":"ad-1","type":"ad.close"}"""), page, true)
        bridge.receive(binary, page, true) // ended
        assertEquals("one report for a broken page", listOf("frame.protocol_violation"), ErrorReporter.bufferedCodes)
        assertEquals("text still goes through", listOf("Loaded", "Close"), events.map { it::class.simpleName })
    }

    // endregion

    // region A container that refuses the ad

    /** [container] laid out at 320×400 in a visible window, as `container()` does for a FrameLayout. */
    private fun <T : android.view.ViewGroup> inWindow(container: T): T {
        val activity = (container.context as Activity)
        activity.setContentView(container)
        container.measure(android.view.View.MeasureSpec.makeMeasureSpec(320, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(400, android.view.View.MeasureSpec.EXACTLY))
        container.layout(0, 0, 320, 400)
        idle()
        container.layout(0, 0, 320, 400)
        org.robolectric.util.ReflectionHelpers.setField(org.robolectric.util.ReflectionHelpers.getField<Any>(container, "mAttachInfo"), "mWindowVisibility", android.view.View.VISIBLE)
        return container
    }

    @Test fun aContainerWhoseAddViewThrowsLeavesTheAdReadyAndTheSlotFree() {
        val makers = listOf<Pair<String, (Activity) -> android.view.ViewGroup>>(
            "a ScrollView with its child" to { a -> android.widget.ScrollView(a).apply { addView(android.view.View(a)) } },
            "a ListView" to { a -> android.widget.ListView(a) },
            // Added, then the container's own hook throws: the host is in it already.
            "a hook that throws" to { a ->
                object : android.widget.FrameLayout(a) {
                    override fun onViewAdded(child: android.view.View) {
                        super.onViewAdded(child)
                        error("not here")
                    }
                }
            },
        )
        for ((what, make) in makers) {
            val owner = owner()
            val recorder = Recorder()
            val ad = overlay(owner, tracking = tracking("impression", "creativeView", "overlayViewDuration"))
            owner.ads[AdFormat.OVERLAY] = ad
            ad.listener = recorder
            val refusing = inWindow(make(activity()))
            val children = refusing.childCount
            try {
                ad.show(refusing)
                org.junit.Assert.fail("$what: shown")
            } catch (error: VideoAdException) {
                assertTrue("$what: $error", error is VideoAdException.CannotShowNow)
            }
            assertTrue("$what: the ad stays ready", ad.isReady && !ad.wasShown)
            assertNull("$what: the slot is free", ScreenSlot.current)
            assertEquals("$what: the owner holds it again", ad, owner.ads[AdFormat.OVERLAY])
            assertEquals("$what: nothing left in the container", children, refusing.childCount)
            assertTrue("$what: nothing fired, nothing heard", events.isEmpty() && recorder.heard.isEmpty())
            // The same ad shows in a good container, and ends once.
            val good = container()
            ad.show(good)
            assertTrue(ad.wasShown)
            ad.dismiss()
            assertEquals(what, listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
            beacons.clear()
        }
    }

    // endregion

    // region A linear screen that cannot start

    /** An Activity whose launches fail, as with the SDK's Activity stripped from the merged manifest. */
    class RefusingActivity : Activity() {
        var failure: RuntimeException = ActivityNotFoundException()

        override fun startActivity(intent: Intent?) = throw failure
    }

    @Test fun aLaunchThatThrowsFailsTheAdOnceAndFreesTheSlot() {
        val failures = listOf(
            ActivityNotFoundException("stripped"),
            SecurityException("not allowed"),
            IllegalStateException("the app's own startActivity refuses"),
            android.util.AndroidRuntimeException("startActivity from outside an Activity context"),
        )
        for (failure in failures) {
            val recorder = Recorder()
            val ad = linear(tracking = tracking("impression"))
            ad.listener = recorder
            val host = Robolectric.buildActivity(RefusingActivity::class.java).setup().get()
            host.failure = failure
            ad.show(host) // accepted: the outcome follows, after show returns
            assertTrue("nothing before show returns", recorder.heard.isEmpty())
            idle()
            assertEquals(failure.javaClass.simpleName, listOf("failed", "ended"), recorder.heard)
            assertEquals(listOf(VideoAd.EndReason.FAILED), recorder.reasons)
            assertTrue(recorder.errors.single() is VideoAdException.CannotShowNow)
            assertTrue("no impression", "impression" !in events)
            assertNull("the slot is free", ScreenSlot.current)
            idle(VideoAd.showTimeoutMillis + 1_000)
            assertEquals("exactly one outcome", 1, recorder.reasons.size)
            ErrorReporter.resetForTesting()
        }
        // Another ad shows.
        overlay().show(container())
    }

    // endregion

    // region An ad that ends before show lets its listener go

    @Test fun anAdThatEndsBeforeShowLetsItsListenerGo() {
        val rejected = overlay().apply { listener = Recorder() }
        rejected.reject()
        assertNull("rejected", rejected.listener)

        val start = Environment.now()
        var now = start
        Environment.now = { now }
        val expired = overlay().apply { listener = Recorder() }
        now = start + 700_000
        assertTrue(runCatching { expired.show(container()) }.exceptionOrNull() is VideoAdException.Invalidated)
        assertNull("expired", expired.listener)
        Environment.now = { start }

        val failed = overlay().apply { listener = Recorder() }
        page(failed, "ad.error", """"code": 405""")
        assertTrue(failed.invalidationReason is VideoAd.InvalidationReason.CreativeFailed)
        assertNull("its creative failed", failed.listener)

        val owner = owner()
        val dropped = overlay(owner).apply { listener = Recorder() }
        owner.ads[AdFormat.OVERLAY] = dropped
        owner.destroy()
        assertNull("its VideoAds was destroyed", dropped.listener)
    }

    // endregion

    // region Prices

    @Test fun equalPricesHashAlikeZeroAndMinusZeroToo() {
        fun price(value: Double) = VideoAd.Price(value, "USD", VideoAd.Price.Precision.EXACT)
        assertEquals(price(0.0), price(-0.0))
        assertEquals(price(0.0).hashCode(), price(-0.0).hashCode())
        assertEquals(price(2.5).hashCode(), price(2.5).hashCode())
        assertEquals(1, setOf(price(0.0), price(-0.0)).size)
        assertNotEquals(price(2.5), price(2.6))
    }

    // endregion
}
