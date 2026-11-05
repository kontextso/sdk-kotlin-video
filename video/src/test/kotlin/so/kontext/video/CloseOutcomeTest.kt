package so.kontext.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.frame.FrameEvent.CloseReason

/** A page's close as a table: the reason, the format and whether the video completed → the beacon and the end. */
class CloseOutcomeTest {
    @Test fun everyCloseOfEveryAd() {
        val skip = CloseOutcome("skip", EndReason.SKIPPED)
        val table = listOf(
            // reason, linear, completed → outcome
            Triple(CloseReason.CLOSE, true, false) to skip,
            Triple(CloseReason.RESUME, true, false) to skip,
            Triple(CloseReason.COMPLETE, true, false) to skip,
            Triple(CloseReason.CLOSE, true, true) to CloseOutcome("close", EndReason.CLOSED),
            Triple(CloseReason.RESUME, true, true) to CloseOutcome("close", EndReason.RESUMED),
            Triple(CloseReason.COMPLETE, true, true) to CloseOutcome(null, EndReason.COMPLETED, completesAd = true),
            Triple(CloseReason.CLOSE, false, false) to CloseOutcome("close", EndReason.CLOSED),
            Triple(CloseReason.RESUME, false, false) to CloseOutcome("close", EndReason.RESUMED),
            Triple(CloseReason.COMPLETE, false, false) to CloseOutcome(null, EndReason.COMPLETED, completesAd = true),
            Triple(CloseReason.CLOSE, false, true) to CloseOutcome("close", EndReason.CLOSED),
        )
        for ((given, expected) in table) {
            val (reason, linear, completed) = given
            assertEquals(expected, closeOutcome(reason, linear, completed), "$reason linear=$linear completed=$completed")
        }
    }
}
