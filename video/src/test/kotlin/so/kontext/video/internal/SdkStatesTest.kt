package so.kontext.video.internal

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.AdFormat
import so.kontext.video.KontextVideo
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.State
import so.kontext.video.VideoAdException
import so.kontext.video.internal.diagnostics.DebugReporter
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.state.SdkState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Every move of the SDK's own state, and what the error and debug reporters do in each state, end to end through
 * the public calls and a local server: the behavior `SdkState` and `SdkLifecycle` must keep.
 */
@RunWith(RobolectricTestRunner::class)
class SdkStatesTest : ParityHarness() {
    private val reportsOn = """{"enabled": true, "reportErrors": true}"""
    private val debugOn = """{"enabled": true, "reportDebug": true}"""
    private val disabled = """{"enabled": false, "disabledReason": "publisherDisabled"}"""
    private val killSwitch = """{"enabled": false, "disabledReason": "killed"}"""

    private val configuration get() = Configuration("test-token") { adServerUrl = baseUrl }

    /** From scratch: `initialize` not called yet. */
    private fun fresh() {
        SdkInitializer.resetForTesting()
        fastReporters()
    }

    /** `/config` held until the returned latch opens, then answering [body]. */
    private fun heldConfig(body: String): CountDownLatch {
        val gate = CountDownLatch(1)
        answer("/v2/config") {
            gate.await(10, TimeUnit.SECONDS)
            MockResponse().setBody(body)
        }
        return gate
    }

    private fun failingConfig() = answer("/v2/config") { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }

    private fun report(detail: String = "x") = ErrorReporter.report(ErrorReport.Code.CREATIVE_FAILED, ErrorReport.Stage.LOAD, detail = detail)

    /** A second `initialize` changes nothing: no `/config`, the same state, no listener told. */
    private fun assertSecondInitializeIgnored() {
        val before = KontextVideo.state
        val configs = requests("/v2/config").size
        var told = 0
        KontextVideo.initialize(Environment.context!!, Configuration("other") { adServerUrl = baseUrl }) { told++ }
        settle()
        assertEquals(before, KontextVideo.state)
        assertEquals("test-token", SdkState.current.configuration!!.publisherToken)
        assertEquals(configs, requests("/v2/config").size)
        assertEquals(0, told)
    }

    // region The SDK's moves

    @Test fun notInitializedMovesToInitializingThenEnabled() {
        fresh()
        assertEquals(State.NotInitialized, KontextVideo.state)
        assertFalse(SdkState.current.isInitialized)
        assertNull(SdkState.current.disabledReason)
        val gate = heldConfig("""{"enabled": true}""")
        var heard: State? = null
        try {
            KontextVideo.initialize(Environment.context!!, configuration) { heard = it }
            assertEquals(State.Initializing, KontextVideo.state)
            assertTrue(SdkState.current.isInitialized)
            await { requests("/v2/config").size == 1 }
            assertSecondInitializeIgnored()
        } finally {
            gate.countDown()
        }
        await { heard != null }
        assertEquals(State.Enabled, heard)
        assertEquals(State.Enabled, KontextVideo.state)
        assertNull(SdkState.current.disabledReason)
        assertSecondInitializeIgnored()
    }

    @Test fun initializingMovesToDisabledWhenConfigSaysSo() {
        assertEquals(State.Disabled("publisherDisabled"), initialize(disabled))
        assertEquals("publisherDisabled", SdkState.current.disabledReason)
        assertTrue(SdkState.current.isInitialized)
        assertSecondInitializeIgnored()
        val call = startFormat(owner(), AdFormat.PAUSE)
        await { call.result != null }
        assertTrue("no retry: disabled is final", call.result!!.exceptionOrNull() is VideoAdException.Disabled)
        assertEquals(1, requests("/v2/config").size)
    }

    @Test fun initializingMovesToFailedAndTheNextAdsRequestMovesItBackToInitializing() {
        failingConfig()
        assertEquals(State.Failed, initialize())
        assertTrue(SdkState.current.isInitialized)
        assertNull(SdkState.current.disabledReason)
        assertSecondInitializeIgnored()
        // A retry that fails again: back to Failed.
        val first = startFormat(owner(), AdFormat.PAUSE)
        await { first.result != null }
        assertTrue("${first.result}", first.result!!.exceptionOrNull() is VideoAdException.InitializationFailed)
        assertEquals(State.Failed, KontextVideo.state)
        assertEquals(2, requests("/v2/config").size)
        // A retry held by the server: Initializing until it answers, then Enabled.
        val gate = heldConfig("""{"enabled": true}""")
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        try {
            val second = startFormat(owner(), AdFormat.PAUSE)
            await { KontextVideo.state == State.Initializing }
            gate.countDown()
            await { second.result != null }
            assertTrue("${second.result}", second.result!!.isSuccess)
        } finally {
            gate.countDown()
        }
        assertEquals(State.Enabled, KontextVideo.state)
        assertEquals(3, requests("/v2/config").size)
    }

    /** A stored install id of the wrong type: KontextKit's `getString` throws while a request body is built. */
    private fun corruptInstallId() =
        Environment.context!!.getSharedPreferences("kontextso", android.content.Context.MODE_PRIVATE).edit().putInt("installId", 7).commit()

    @Test fun aConfigBodyThatCannotBeBuiltMovesToFailedNotInitializing() {
        corruptInstallId()
        assertEquals("one outcome, heard by the listener", State.Failed, initialize())
        assertEquals(0, requests("/v2/config").size)
        // The next ads request tries /config again, and hears a typed failure.
        val call = startFormat(owner(), AdFormat.PAUSE)
        await { call.result != null }
        assertTrue("${call.result}", call.result!!.exceptionOrNull() is VideoAdException.InitializationFailed)
        assertEquals(State.Failed, KontextVideo.state)
    }

    @Test fun anAdsBodyThatCannotBeBuiltIsARequestFailure() {
        corruptInstallId()
        val call = startFormat(owner(), AdFormat.PAUSE)
        await { call.result != null }
        val error = call.result!!.exceptionOrNull()
        assertTrue("$error", error is VideoAdException.Request && error.failure == VideoAdException.RequestFailure.BadResponse)
        assertEquals(0, requests("/v2/video/ads").size)
    }

    @Test fun aReportBatchThatCannotBeBuiltIsDroppedWithoutStoppingTheApp() {
        assertEquals(State.Enabled, initialize(reportsOn))
        corruptInstallId()
        report()
        settle()
        assertEquals(0, requests("/v2/error").size)
    }

    @Test fun anAdsAnswerIsDecodedOffTheMainThread() {
        var onMain: Boolean? = null
        so.kontext.video.internal.network.AdsFetcher.decode = {
            onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            so.kontext.video.internal.payloads.AdsResponse.parse(it)
        }
        try {
            answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
            assertTrue(format(owner(), AdFormat.PAUSE).isSuccess)
            assertEquals(false, onMain)
        } finally {
            so.kontext.video.internal.network.AdsFetcher.decode = { so.kontext.video.internal.payloads.AdsResponse.parse(it) }
        }
    }

    @Test fun failedMovesToDisabledThroughTheRetry() {
        failingConfig()
        assertEquals(State.Failed, initialize())
        answer("/v2/config", disabled)
        val call = startFormat(owner(), AdFormat.PAUSE)
        await { call.result != null }
        assertTrue("${call.result}", call.result!!.exceptionOrNull() is VideoAdException.Disabled)
        assertEquals(State.Disabled("publisherDisabled"), KontextVideo.state)
    }

    @Test fun anotherProcessMovesStraightToDisabled() {
        fresh()
        val app = Environment.context!!
        Environment.processName = { "${app.packageName}:playback" }
        KontextVideo.initialize(app, configuration)
        assertEquals(State.Disabled(SdkInitializer.NOT_MAIN_PROCESS), KontextVideo.state)
        assertEquals(SdkInitializer.NOT_MAIN_PROCESS, SdkState.current.disabledReason)
        assertTrue(SdkState.current.isInitialized)
        assertSecondInitializeIgnored()
    }

    @Test fun aKillSwitchInAnAdsAnswerMovesEnabledToDisabled() {
        assertEquals(State.Enabled, initialize())
        answer("/v2/video/ads", killSwitch)
        val call = startFormat(owner(), AdFormat.PAUSE)
        await { call.result != null }
        assertTrue("${call.result}", call.result!!.exceptionOrNull() is VideoAdException.Disabled)
        assertEquals(State.Disabled("killed"), KontextVideo.state)
        assertSecondInitializeIgnored()
        // Another answer in flight meanwhile may carry another reason: the last one stays.
        SdkInitializer.disable("killed")
        assertEquals(State.Disabled("killed"), KontextVideo.state)
        SdkInitializer.disable("other")
        assertEquals(State.Disabled("other"), KontextVideo.state)
    }

    // endregion

    // region Error reports across the states

    @Test fun errorReportsWaitWhileNotInitializedAndInitializingThenLeaveOnceConfigTurnsThemOn() {
        fresh()
        report("before")
        val gate = heldConfig(reportsOn)
        var heard: State? = null
        try {
            KontextVideo.initialize(Environment.context!!, configuration) { heard = it }
            report("during")
            settle()
            assertEquals(2, ErrorReporter.bufferedCount)
            assertTrue(requests("/v2/error").isEmpty())
        } finally {
            gate.countDown()
        }
        await { heard != null }
        await { errorReports().size == 2 }
        assertEquals(listOf("before", "during"), errorReports().map { it.str("detail") })
    }

    @Test fun errorReportsWaitWhileFailedAndLeaveOnceARetryTurnsThemOn() {
        failingConfig()
        assertEquals(State.Failed, initialize())
        report("failed")
        settle()
        assertEquals(listOf("config.failed", "ad.creative_failed"), ErrorReporter.bufferedCodes)
        answer("/v2/config", reportsOn)
        answer("/v2/video/ads", Fixtures.ads(Fixtures.entry("pause", Fixtures.pause())).toString())
        format(owner(), AdFormat.PAUSE).getOrThrow()
        await { errorReports().size == 2 }
    }

    @Test fun errorReportsWaitForeverInAnotherProcess() {
        fresh()
        val app = Environment.context!!
        Environment.processName = { "${app.packageName}:playback" }
        KontextVideo.initialize(app, configuration)
        report()
        settle()
        assertEquals("kept, never sent: no /config answers there", 1, ErrorReporter.bufferedCount)
        assertTrue(requests("/v2/error").isEmpty())
    }

    @Test fun errorReportsAreDroppedWhenConfigDisablesOrLeavesThemOut() {
        fresh()
        report()
        assertEquals(State.Disabled("publisherDisabled"), initialize(disabled).also { report() })
        assertEquals(0, ErrorReporter.bufferedCount)
        answers.remove("/v2/config")
        fresh()
        report()
        assertEquals(State.Enabled, initialize())
        report()
        settle()
        assertEquals(0, ErrorReporter.bufferedCount)
        assertTrue(requests("/v2/error").isEmpty())
    }

    @Test fun errorReportsStayOnAfterTheKillSwitch() {
        assertEquals(State.Enabled, initialize(reportsOn))
        answer("/v2/video/ads", killSwitch)
        format(owner(), AdFormat.PAUSE)
        assertEquals(State.Disabled("killed"), KontextVideo.state)
        report("after")
        await { errorReports().any { it.str("detail") == "after" } }
    }

    // endregion

    // region Debug reports across the states

    @Test fun debugEventsLeaveOnlyOnceConfigAsksAndStillAfterTheKillSwitch() {
        fresh()
        DebugReporter.event("before")
        assertFalse(DebugReporter.isEnabled)
        val gate = heldConfig(debugOn)
        var heard: State? = null
        try {
            KontextVideo.initialize(Environment.context!!, configuration) { heard = it }
            DebugReporter.event("during")
            assertFalse(DebugReporter.isEnabled)
        } finally {
            gate.countDown()
        }
        await { heard != null }
        assertTrue(DebugReporter.isEnabled)
        await { debugNames().contains("config.response") }
        answer("/v2/video/ads", killSwitch)
        format(owner(), AdFormat.PAUSE)
        assertEquals(State.Disabled("killed"), KontextVideo.state)
        assertTrue("on after the kill switch", DebugReporter.isEnabled)
        DebugReporter.event("after")
        await { debugNames().contains("after") }
        assertFalse("nothing from before /config answered", debugNames().any { it == "before" || it == "during" })
    }

    @Test fun debugEventsStayOffWhenConfigFailsDisablesOrLeavesThemOut() {
        failingConfig()
        assertEquals(State.Failed, initialize())
        assertFalse(DebugReporter.isEnabled)
        answers.remove("/v2/config")
        assertEquals(State.Disabled("publisherDisabled"), initialize(disabled))
        assertFalse(DebugReporter.isEnabled)
        answers.remove("/v2/config")
        assertEquals(State.Enabled, initialize())
        assertFalse(DebugReporter.isEnabled)
        DebugReporter.event("tick")
        settle()
        assertTrue(requests("/v2/debug").isEmpty())
    }

    // endregion
}
