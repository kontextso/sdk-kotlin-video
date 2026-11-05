package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.frame.FrameEvent.CloseReason
import so.kontext.video.internal.frame.FrameEvent.CloseReason.Outcome

/** A page's close as a table: the reason, the format and whether the video completed → the beacon and the end. */
class CloseOutcomeTest {
    @Test fun everyCloseOfEveryAd() {
        val skip = Outcome("skip", EndReason.SKIPPED)
        val table = listOf(
            // reason, linear, completed → outcome
            Triple(CloseReason.CLOSE, true, false) to skip,
            Triple(CloseReason.RESUME, true, false) to skip,
            Triple(CloseReason.COMPLETE, true, false) to skip,
            Triple(CloseReason.CLOSE, true, true) to Outcome("close", EndReason.CLOSED),
            Triple(CloseReason.RESUME, true, true) to Outcome("close", EndReason.RESUMED),
            Triple(CloseReason.COMPLETE, true, true) to Outcome(null, EndReason.COMPLETED, completes = true),
            Triple(CloseReason.CLOSE, false, false) to Outcome("close", EndReason.CLOSED),
            Triple(CloseReason.RESUME, false, false) to Outcome("close", EndReason.RESUMED),
            Triple(CloseReason.COMPLETE, false, false) to Outcome(null, EndReason.COMPLETED, completes = true),
            Triple(CloseReason.CLOSE, false, true) to Outcome("close", EndReason.CLOSED),
        )
        for ((given, expected) in table) {
            val (reason, linear, completed) = given
            assertEquals(expected, reason.outcome(linear, completed), "$reason linear=$linear completed=$completed")
        }
    }
}
