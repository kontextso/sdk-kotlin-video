package so.kontext.video.ads.overlay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import so.kontext.video.Constants
import so.kontext.video.model.VideoAd
import so.kontext.video.model.VideoAdCreative
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.model.VideoAdTracking

/**
 * Drives the placement's main-thread scope with a test dispatcher: `Main`
 * is swapped for [StandardTestDispatcher] before the placement is built, so
 * `trackPlayhead`'s loop runs on virtual time. Nothing here mounts a view.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayAdTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Runs what is due now, then moves virtual time one poll interval forward and runs what became due. */
    private fun tick(times: Int = 1) {
        dispatcher.scheduler.runCurrent()
        repeat(times) {
            dispatcher.scheduler.advanceTimeBy(Constants.PLAYHEAD_POLL_MS)
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `playheadMs follows the lambda every poll interval`() {
        val overlay = OverlayAd("v1") {}
        var position = 1_000L
        overlay.trackPlayhead { position }
        dispatcher.scheduler.runCurrent()
        assertEquals(1_000L, overlay.playheadMs)

        position = 1_250L
        tick()
        assertEquals(1_250L, overlay.playheadMs)

        position = 4_000L
        tick(times = 3)
        assertEquals(4_000L, overlay.playheadMs)
    }

    @Test
    fun `tracking opens and closes windows`() {
        val overlay = OverlayAd("v1") {}
        overlay.append(listOf(windowedAd("a", fromMs = 3_000, toMs = 9_000)))
        val opened = mutableListOf<String>()
        val closed = mutableListOf<String>()
        overlay.onWindowOpen = { opened += it.adId }
        overlay.onWindowClose = { closed += it.adId }

        var position = 0L
        overlay.trackPlayhead { position }
        tick()
        assertEquals(emptyList<String>(), opened)
        assertNull(overlay.openWindowAd)

        position = 3_000L
        tick()
        assertEquals(listOf("a"), opened)
        assertEquals("a", overlay.openWindowAd?.adId)

        position = 5_000L
        tick()
        assertEquals(listOf("a"), opened, "a window opens once")

        position = 9_000L
        tick()
        assertEquals(listOf("a"), closed)
        assertNull(overlay.openWindowAd)
    }

    @Test
    fun `stopTrackingPlayhead stops the polling and keeps the last value`() {
        val overlay = OverlayAd("v1") {}
        var position = 500L
        overlay.trackPlayhead { position }
        tick()
        assertEquals(500L, overlay.playheadMs)

        overlay.stopTrackingPlayhead()
        position = 9_000L
        tick(times = 4)
        assertEquals(500L, overlay.playheadMs)
    }

    @Test
    fun `destroy stops the polling and trackPlayhead is a no-op afterwards`() {
        val overlay = OverlayAd("v1") {}
        var position = 500L
        var reads = 0
        overlay.trackPlayhead {
            reads++
            position
        }
        tick()
        val readsBeforeDestroy = reads

        overlay.destroy()
        position = 9_000L
        tick(times = 4)
        assertEquals(500L, overlay.playheadMs)
        assertEquals(readsBeforeDestroy, reads)

        overlay.trackPlayhead {
            reads++
            position
        }
        tick(times = 2)
        assertEquals(readsBeforeDestroy, reads, "a destroyed placement never reads the playhead")
        assertEquals(500L, overlay.playheadMs)
    }

    @Test
    fun `a throwing lambda stops tracking without crashing`() {
        val overlay = OverlayAd("v1") {}
        var reads = 0
        overlay.trackPlayhead {
            reads++
            if (reads >= 2) error("player released") else 700L
        }
        tick(times = 4)
        assertEquals(2, reads, "the lambda is not called again after it throws")
        assertEquals(700L, overlay.playheadMs)
    }

    @Test
    fun `calling trackPlayhead again replaces the lambda`() {
        val overlay = OverlayAd("v1") {}
        var firstReads = 0
        overlay.trackPlayhead {
            firstReads++
            100L
        }
        tick()
        overlay.trackPlayhead { 200L }
        val firstReadsAfterReplace = firstReads
        tick(times = 3)
        assertEquals(firstReadsAfterReplace, firstReads, "the first lambda is never read again")
        assertEquals(200L, overlay.playheadMs)
    }

    private fun windowedAd(id: String, fromMs: Long, toMs: Long) = VideoAd(
        format = VideoAdFormat.OVERLAY,
        adId = id,
        ecpm = 1.0,
        trigger = VideoAd.Trigger(kind = "range", fromMs = fromMs, toMs = toMs),
        creative = VideoAdCreative(image = VideoAdCreative.Image(url = "https://a.test/$id.png", w = 300, h = 100)),
        tracking = VideoAdTracking(
            impression = "https://a.test/$id/impression",
            viewable = "https://a.test/$id/viewable",
            notViewable = "https://a.test/$id/not_viewable",
            viewUndetermined = "https://a.test/$id/view_undetermined",
            creativeView = "https://a.test/$id/creative_view",
            close = "https://a.test/$id/close",
            overlayViewDuration = "https://a.test/$id/overlay_view_duration",
            clickThrough = "https://a.test/$id/click",
            clickTracking = "https://a.test/$id/click_tracking",
        ),
    )
}
