package so.kontext.video.internal.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.KontextVideo.State

/** The SDK's rules as a pure table: every move from every state, legal or refused. */
class SdkLifecycleTest {
    private val states: List<State> = listOf(
        State.NotInitialized,
        State.Initializing,
        State.Enabled,
        State.Disabled("publisherDisabled"),
        State.Failed,
    )

    private fun name(state: State) = state::class.simpleName!!

    @Test fun everyMoveFromEveryState() {
        // from → the states it may move to; every other move is refused.
        val legal = mapOf(
            "NotInitialized" to setOf("Initializing", "Disabled"),
            "Initializing" to setOf("Enabled", "Disabled", "Failed"),
            "Enabled" to setOf("Disabled"),
            "Disabled" to emptySet(), // but to another reason: below
            "Failed" to setOf("Initializing"),
        )
        for (from in states) {
            for (to in states) {
                val expected = name(to) in legal.getValue(name(from))
                assertEquals(expected, SdkLifecycle.canMove(from, to), "${name(from)} → ${name(to)}")
            }
        }
    }

    @Test fun disabledMovesOnlyToAnotherReason() {
        assertTrue(SdkLifecycle.canMove(State.Disabled("publisherDisabled"), State.Disabled("killed")))
        assertFalse(SdkLifecycle.canMove(State.Disabled("killed"), State.Disabled("killed")))
    }
}
