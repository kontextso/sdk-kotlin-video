package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.VideoAdException
import so.kontext.video.internal.core.AdState
import so.kontext.video.internal.core.AdState.Phase

/**
 * The ad's state as a table: every move from every phase, legal or refused, the flags each phase gives, and the
 * milestones, each recorded once and never undone.
 */
class AdStateTest {
    private val phases: List<Phase> = listOf(
        Phase.Offered,
        Phase.Invalidated(InvalidationReason.Expired),
        Phase.Reserved,
        Phase.OnScreen,
        Phase.Ending(EndReason.CLOSED),
        Phase.Ended(EndReason.CLOSED),
    )

    private fun name(phase: Phase) = phase::class.simpleName!!

    /** A state walked to [phase] by legal moves. */
    private fun at(phase: Phase): AdState {
        val path = when (phase) {
            Phase.Offered -> emptyList()
            is Phase.Invalidated -> listOf(phase)
            Phase.Reserved -> listOf(Phase.Reserved)
            Phase.OnScreen -> listOf(Phase.Reserved, Phase.OnScreen)
            is Phase.Ending -> listOf(Phase.Reserved, Phase.OnScreen, phase)
            is Phase.Ended -> listOf(Phase.Reserved, Phase.OnScreen, Phase.Ending(phase.reason), phase)
        }
        return AdState().also { state -> path.forEach { assertTrue(state.transition(it)) } }
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
                val state = at(from)
                val moved = state.transition(to)
                val expected = name(to) in legal.getValue(name(from))
                assertEquals(expected, moved, "${name(from)} → ${name(to)}")
                assertEquals(if (expected) to else from, state.phase, "${name(from)} → ${name(to)}")
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
            val state = at(phase)
            assertEquals(flags.getValue(name(phase)), listOf(state.wasShown, state.hasEnded, state.isPresented), name(phase))
        }
        assertEquals(InvalidationReason.Expired, at(Phase.Invalidated(InvalidationReason.Expired)).invalidationReason)
        assertNull(at(Phase.Ended(EndReason.CLOSED)).invalidationReason)
    }

    @Test fun anAdEndedDuringItsAttachWasNeverPresented() {
        val state = AdState()
        state.transition(Phase.Reserved)
        assertTrue(state.transition(Phase.Ending(EndReason.INTERRUPTED)))
        assertTrue(state.hasEnded)
        assertFalse(state.isPresented, "no end beacons for an ad that never reached the screen")
    }

    @Test fun anUnreservedAdIsOfferedAgainAndCanBeShownAgain() {
        val state = AdState()
        state.transition(Phase.Reserved)
        assertTrue(state.transition(Phase.Offered))
        assertFalse(state.wasShown)
        assertTrue(state.transition(Phase.Reserved))
    }

    @Test fun aSecondEndChangesNothing() {
        val state = at(Phase.Ending(EndReason.SKIPPED))
        assertFalse(state.transition(Phase.Ending(EndReason.CLOSED)))
        assertEquals(Phase.Ending(EndReason.SKIPPED), state.phase)
    }

    @Test fun eachMilestoneIsRecordedOnceAndNeverUndone() {
        val state = AdState()
        assertEquals(listOf(false, false, false), listOf(state.loaded, state.completed, state.impressionRecorded))
        state.markLoaded()
        state.markLoaded()
        assertTrue(state.loaded)
        assertTrue(state.markImpression(), "the first impression is recorded")
        assertFalse(state.markImpression(), "a second one is not")
        assertTrue(state.impressionRecorded)
        state.markCompleted()
        assertTrue(state.completed)
        assertEquals(Phase.Offered, state.phase, "a milestone does not move the phase")
        for (next in listOf(Phase.Reserved, Phase.OnScreen, Phase.Ending(EndReason.COMPLETED), Phase.Ended(EndReason.COMPLETED))) {
            assertTrue(state.transition(next))
            assertEquals(listOf(true, true, true), listOf(state.loaded, state.completed, state.impressionRecorded), "kept at ${name(next)}")
        }
        assertFalse(state.markImpression(), "still recorded after the end")
    }

    @Test fun anInvalidatedAdKeepsItsMilestones() {
        val state = AdState()
        state.markLoaded()
        assertTrue(state.transition(Phase.Invalidated(InvalidationReason.Rejected)))
        assertTrue(state.loaded)
    }

    @Test fun theFailureIsReadBackAndTheFirstStays() {
        val error = VideoAdException.PresenterUnavailable()
        val state = at(Phase.OnScreen)
        assertNull(state.failure)
        state.markFailed(error)
        state.markFailed(VideoAdException.PresenterUnavailable())
        assertSame(error, state.failure, "the first failure stays")
        assertEquals(Phase.OnScreen, state.phase, "a milestone does not move the phase")
        assertTrue(state.transition(Phase.Ending(EndReason.FAILED)))
        assertTrue(state.transition(Phase.Ended(EndReason.FAILED)))
        assertSame(error, state.failure, "kept after onAdEnded")
    }
}
