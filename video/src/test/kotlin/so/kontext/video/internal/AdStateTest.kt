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
import so.kontext.video.internal.state.AdLifecycle
import so.kontext.video.internal.state.AdLifecycle.Phase
import so.kontext.video.internal.state.AdState

/**
 * The ad's state: every move from every phase, applied or refused and changing nothing, the flags each phase gives,
 * and the milestones, each recorded once and never undone. The rule itself is [AdLifecycleTest]'s table.
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
        return AdState().also { state -> path.forEach { assertTrue(state.moveTo(it)) } }
    }

    @Test fun everyMoveIsAppliedOnlyWhenLegal() {
        for (from in phases) {
            for (to in phases) {
                val state = at(from)
                val expected = AdLifecycle.canMove(from, to)
                assertEquals(expected, state.moveTo(to), "${name(from)} → ${name(to)}")
                assertEquals(if (expected) to else from, state.phase, "${name(from)} → ${name(to)}")
            }
        }
    }

    @Test fun onlyTheFirstMoveOnScreenRecordsItAndNothingUndoesIt() {
        val state = AdState()
        assertTrue(state.moveTo(Phase.Reserved))
        assertFalse(state.wasOnScreen)
        assertTrue(state.moveTo(Phase.OnScreen))
        assertTrue(state.wasOnScreen)
        assertTrue(state.moveTo(Phase.Ending(EndReason.CLOSED)))
        assertTrue(state.moveTo(Phase.Ended(EndReason.CLOSED)))
        assertTrue(state.wasOnScreen, "kept after the end")
    }

    @Test fun theFlagsOfEachPhase() {
        // phase → wasShown, hasEnded, wasOnScreen
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
            assertEquals(flags.getValue(name(phase)), listOf(state.wasShown, state.hasEnded, state.wasOnScreen), name(phase))
        }
        assertEquals(InvalidationReason.Expired, at(Phase.Invalidated(InvalidationReason.Expired)).invalidationReason)
        assertNull(at(Phase.Ended(EndReason.CLOSED)).invalidationReason)
    }

    @Test fun anAdEndedDuringItsAttachWasNeverOnScreen() {
        val state = AdState()
        state.moveTo(Phase.Reserved)
        assertTrue(state.moveTo(Phase.Ending(EndReason.INTERRUPTED)))
        assertTrue(state.hasEnded)
        assertFalse(state.wasOnScreen, "no end beacons for an ad that never reached the screen")
    }

    @Test fun anUnreservedAdIsOfferedAgainAndCanBeShownAgain() {
        val state = AdState()
        state.moveTo(Phase.Reserved)
        assertTrue(state.moveTo(Phase.Offered))
        assertFalse(state.wasShown)
        assertTrue(state.moveTo(Phase.Reserved))
    }

    @Test fun aSecondEndChangesNothing() {
        val state = at(Phase.Ending(EndReason.SKIPPED))
        assertFalse(state.moveTo(Phase.Ending(EndReason.CLOSED)))
        assertEquals(Phase.Ending(EndReason.SKIPPED), state.phase)
    }

    @Test fun eachMilestoneIsRecordedOnceAndNeverUndone() {
        val state = AdState()
        assertEquals(listOf(false, false, false), listOf(state.isLoaded, state.isCompleted, state.hasImpression))
        state.markLoaded()
        state.markLoaded()
        assertTrue(state.isLoaded)
        assertTrue(state.markImpression(), "the first impression is recorded")
        assertFalse(state.markImpression(), "a second one is not")
        assertTrue(state.hasImpression)
        state.markCompleted()
        assertTrue(state.isCompleted)
        assertEquals(Phase.Offered, state.phase, "a milestone does not move the phase")
        for (next in listOf(Phase.Reserved, Phase.OnScreen, Phase.Ending(EndReason.COMPLETED), Phase.Ended(EndReason.COMPLETED))) {
            assertTrue(state.moveTo(next))
            assertEquals(listOf(true, true, true), listOf(state.isLoaded, state.isCompleted, state.hasImpression), "kept at ${name(next)}")
        }
        assertFalse(state.markImpression(), "still recorded after the end")
    }

    @Test fun anInvalidatedAdKeepsItsMilestones() {
        val state = AdState()
        state.markLoaded()
        assertTrue(state.moveTo(Phase.Invalidated(InvalidationReason.Rejected)))
        assertTrue(state.isLoaded)
    }

    @Test fun theFailureIsReadBackAndTheFirstStays() {
        val error = VideoAdException.PresenterUnavailable()
        val state = at(Phase.OnScreen)
        assertNull(state.failure)
        state.markFailed(error)
        state.markFailed(VideoAdException.PresenterUnavailable())
        assertSame(error, state.failure, "the first failure stays")
        assertEquals(Phase.OnScreen, state.phase, "a milestone does not move the phase")
        assertTrue(state.moveTo(Phase.Ending(EndReason.FAILED)))
        assertTrue(state.moveTo(Phase.Ended(EndReason.FAILED)))
        assertSame(error, state.failure, "kept after onAdEnded")
    }
}
