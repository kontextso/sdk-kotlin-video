package so.kontext.video.ads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import so.kontext.video.ads.pause.PauseAd
import so.kontext.video.model.VideoAd
import so.kontext.video.model.VideoAdCreative
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.model.VideoAdTracking

/** The shared queue behaviour, through the simplest placement. Nothing here mounts a view. */
class AdPlacementTest {
    @Test
    fun `nextAd is the first pending ad while ready, null otherwise`() {
        val pause = PauseAd("v1") {}
        assertNull(pause.nextAd)

        pause.append(listOf(ad("a"), ad("b")))
        assertEquals("a", pause.nextAd?.adId)
        assertEquals(listOf("a", "b"), pause.pending.map { it.adId })

        pause.destroy()
        assertNull(pause.nextAd)
    }

    private fun ad(id: String) = VideoAd(
        format = VideoAdFormat.PAUSE,
        adId = id,
        ecpm = 2.5,
        trigger = VideoAd.Trigger(kind = "pause"),
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
