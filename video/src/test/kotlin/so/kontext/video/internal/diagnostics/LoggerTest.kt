package so.kontext.video.internal.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.SdkTest

/** The log: only what its level lets through reaches the sink. */
@RunWith(RobolectricTestRunner::class)
class LoggerTest : SdkTest() {
    @Test fun theLogShowsOnlyWhatItsLevelLets() {
        val lines = mutableListOf<String>()
        Logger.sink = { _, message -> lines += message }
        try {
            Logger.level = Configuration.LogLevel.WARNING
            Logger.debug { "debug" }
            Logger.info { "info" }
            Logger.warning { "warning" }
            Logger.error { "error" }
            Logger.level = Configuration.LogLevel.NONE
            Logger.error { "silent" }
            assertEquals(listOf("warning", "error"), lines)
        } finally {
            Logger.sink = null
            Logger.level = Configuration.LogLevel.WARNING
        }
    }
}
