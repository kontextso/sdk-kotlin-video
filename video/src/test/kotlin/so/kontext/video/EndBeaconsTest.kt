package so.kontext.video

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.payloads.AdPayload

/**
 * The beacons of an ad's end, by its end reason, for an ad shown and ended before its impression: a video that
 * never reached its first frame, and an image ended before its first layout. A characterization of the behaviour.
 *
 * ```
 *   creative   no impression, ended as                          beacons after creativeView
 *   video      COMPLETED, DISMISSED, DESTROYED, INTERRUPTED     close, overlayViewDuration
 *   video      SKIPPED, CLOSED, RESUMED, FAILED                 overlayViewDuration
 *   image      any                                              overlayViewDuration
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class EndBeaconsTest : SdkTest() {
    private val all = tracking("creativeView", "impression", "close", "skip", "error", "overlayViewDuration")

    private fun overlay(creative: String): VideoAd {
        val json = Fixtures.overlay(tracking = all).replace("\"creative\": \"image\"", "\"creative\": \"$creative\"")
        return loaded(AdPayload.parse(Fixtures.json(json)))
    }

    private fun beaconsOfEnd(creative: String, reason: EndReason): List<String> {
        beacons.clear()
        val ad = overlay(creative)
        ad.show(container())
        if (reason == EndReason.FAILED) ad.fail(VideoAdException.CreativeFailed(405)) else ad.end(reason)
        return events
    }

    @Test fun aVideoWithNoImpression() {
        val closes = setOf(EndReason.COMPLETED, EndReason.DISMISSED, EndReason.DESTROYED, EndReason.INTERRUPTED)
        for (reason in EndReason.entries) {
            val expected = listOf("creativeView") + (if (reason in closes) listOf("close") else emptyList()) + "overlayViewDuration"
            assertEquals("$reason", expected, beaconsOfEnd("video", reason))
        }
    }

    @Test fun anImageWithNoImpression() {
        for (reason in EndReason.entries) {
            assertEquals("$reason", listOf("creativeView", "overlayViewDuration"), beaconsOfEnd("image", reason))
        }
    }
}
