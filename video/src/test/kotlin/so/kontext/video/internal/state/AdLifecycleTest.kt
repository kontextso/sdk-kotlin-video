package so.kontext.video.internal.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.internal.state.AdLifecycle.Phase

/** The lifecycle's rules as a pure table: every move from every phase, legal or refused. */
class AdLifecycleTest {
    private val phases: List<Phase> = listOf(
        Phase.Offered,
        Phase.Invalidated(InvalidationReason.Expired),
        Phase.Reserved,
        Phase.OnScreen,
        Phase.Ending(EndReason.CLOSED),
        Phase.Ended(EndReason.CLOSED),
    )

    private fun name(phase: Phase) = phase::class.simpleName!!

    @Test fun everyMoveFromEveryPhase() {
        // from → the phases it may move to; every other move is refused.
        val legal = mapOf(
            "Offered" to setOf("Invalidated", "Reserved"),
            "Invalidated" to emptySet(),
            "Reserved" to setOf("OnScreen", "Ending", "Offered"),
            "OnScreen" to setOf("Ending"),
            "Ending" to setOf("Ended"),
            "Ended" to emptySet(),
        )
        for (from in phases) {
            for (to in phases) {
                val expected = name(to) in legal.getValue(name(from))
                assertEquals(expected, AdLifecycle.canMove(from, to), "${name(from)} → ${name(to)}")
            }
        }
    }
}
