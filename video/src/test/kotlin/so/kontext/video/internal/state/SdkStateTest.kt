package so.kontext.video.internal.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.State

/** The SDK's facts: the phase moves only as `SdkLifecycle` allows, the rest is recorded once or derived. */
class SdkStateTest {
    @Test fun startsNotInitializedWithNothingRecorded() {
        val state = SdkState()
        assertEquals(State.NotInitialized, state.phase)
        assertFalse(state.isInitialized)
        assertNull(state.disabledReason)
        assertNull(state.configuration)
        assertNull(state.isErrorReportingOn, "error reports wait for /config")
        assertFalse(state.isDebugReportingOn)
    }

    @Test fun movesOnlyAsTheLifecycleAllows() {
        val state = SdkState()
        assertFalse(state.moveTo(State.Enabled), "not before /config")
        assertEquals(State.NotInitialized, state.phase)
        assertTrue(state.moveTo(State.Initializing))
        assertTrue(state.isInitialized)
        assertTrue(state.moveTo(State.Failed))
        assertFalse(state.moveTo(State.Disabled("killed")), "no kill switch while failed")
        assertTrue(state.moveTo(State.Initializing))
        assertTrue(state.moveTo(State.Enabled))
        assertTrue(state.moveTo(State.Disabled("killed")))
        assertEquals("killed", state.disabledReason)
        assertFalse(state.moveTo(State.Disabled("killed")), "the same reason is no move")
        assertTrue(state.moveTo(State.Disabled("other")))
        assertFalse(state.moveTo(State.Initializing))
        assertFalse(state.moveTo(State.Enabled))
        assertEquals(State.Disabled("other"), state.phase)
        assertTrue(state.isInitialized)
    }

    @Test fun anotherProcessIsDisabledStraightAway() {
        val state = SdkState()
        assertTrue(state.moveTo(State.Disabled("not the app's main process")))
        assertTrue(state.isInitialized)
        assertEquals("not the app's main process", state.disabledReason)
    }

    @Test fun theConfigurationIsRecordedOnce() {
        val state = SdkState()
        val first = Configuration("first")
        state.recordConfiguration(first)
        state.recordConfiguration(Configuration("second"))
        assertSame(first, state.configuration)
    }

    @Test fun errorReportingOffIsFinal() {
        val state = SdkState()
        state.recordErrorReporting(true)
        assertEquals(true, state.isErrorReportingOn)
        state.recordErrorReporting(false)
        state.recordErrorReporting(true)
        assertEquals(false, state.isErrorReportingOn)
    }

    @Test fun debugReportingFollowsTheLastAnswer() {
        val state = SdkState()
        state.recordDebugReporting(true)
        assertTrue(state.isDebugReportingOn)
        state.recordDebugReporting(false)
        assertFalse(state.isDebugReportingOn)
    }

    @Test fun enabledForTestingWithoutConfig() {
        val state = SdkState()
        val configuration = Configuration("test-token")
        state.enableForTesting(configuration)
        assertEquals(State.Enabled, state.phase)
        assertSame(configuration, state.configuration)
        assertNull(state.isErrorReportingOn)
    }
}
