package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.internal.core.AdLifecycle
import so.kontext.video.internal.core.AdLifecycle.Phase

/** The ad's lifecycle as a table: every move from every phase, legal or refused, and the flags each phase gives. */
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

    /** A lifecycle walked to [phase] by legal moves. */
    private fun at(phase: Phase): AdLifecycle {
        val path = when (phase) {
            Phase.Offered -> emptyList()
            is Phase.Invalidated -> listOf(phase)
            Phase.Reserved -> listOf(Phase.Reserved)
            Phase.OnScreen -> listOf(Phase.Reserved, Phase.OnScreen)
            is Phase.Ending -> listOf(Phase.Reserved, Phase.OnScreen, phase)
            is Phase.Ended -> listOf(Phase.Reserved, Phase.OnScreen, Phase.Ending(phase.reason), phase)
        }
        return AdLifecycle().also { lifecycle -> path.forEach { assertTrue(lifecycle.transition(it)) } }
    }

    @Test fun everyMoveFromEveryPhase() {
        // from → the phases it may move to; every other move is refused and changes nothing.
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
                val lifecycle = at(from)
                val moved = lifecycle.transition(to)
                val expected = name(to) in legal.getValue(name(from))
                assertEquals(expected, moved, "${name(from)} → ${name(to)}")
                assertEquals(if (expected) to else from, lifecycle.phase, "${name(from)} → ${name(to)}")
            }
        }
    }

    @Test fun theFlagsOfEachPhase() {
        // phase → wasShown, hasEnded, isPresented
        val flags = mapOf(
            "Offered" to listOf(false, false, false),
            "Invalidated" to listOf(false, false, false),
            "Reserved" to listOf(true, false, false),
            "OnScreen" to listOf(true, false, true),
            "Ending" to listOf(true, true, true),
            "Ended" to listOf(true, true, true),
        )
        for (phase in phases) {
            val lifecycle = at(phase)
            assertEquals(flags.getValue(name(phase)), listOf(lifecycle.wasShown, lifecycle.hasEnded, lifecycle.isPresented), name(phase))
        }
        assertEquals(InvalidationReason.Expired, at(Phase.Invalidated(InvalidationReason.Expired)).invalidationReason)
        assertNull(at(Phase.Ended(EndReason.CLOSED)).invalidationReason)
    }

    @Test fun anAdEndedDuringItsAttachWasNeverPresented() {
        val lifecycle = AdLifecycle()
        lifecycle.transition(Phase.Reserved)
        assertTrue(lifecycle.transition(Phase.Ending(EndReason.INTERRUPTED)))
        assertTrue(lifecycle.hasEnded)
        assertFalse(lifecycle.isPresented, "no end beacons for an ad that never reached the screen")
    }

    @Test fun anUnreservedAdIsOfferedAgainAndCanBeShownAgain() {
        val lifecycle = AdLifecycle()
        lifecycle.transition(Phase.Reserved)
        assertTrue(lifecycle.transition(Phase.Offered))
        assertFalse(lifecycle.wasShown)
        assertTrue(lifecycle.transition(Phase.Reserved))
    }

    @Test fun aSecondEndChangesNothing() {
        val lifecycle = at(Phase.Ending(EndReason.SKIPPED))
        assertFalse(lifecycle.transition(Phase.Ending(EndReason.CLOSED)))
        assertEquals(Phase.Ending(EndReason.SKIPPED), lifecycle.phase)
    }
}
