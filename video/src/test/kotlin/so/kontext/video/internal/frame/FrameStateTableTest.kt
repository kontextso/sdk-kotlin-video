package so.kontext.video.internal.frame

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.platform.Environment

/**
 * Every message of the frame protocol in every state of the bridge, through the real bridge: what reaches the ad.
 * A characterization of the behaviour: each cell is the event delivered (its class), `-` for dropped, and
 * `+end` when the bridge ended with it.
 *
 * ```
 *   column    the bridge before the message
 *   loading   made, its page not ready
 *   loaded    ad.loaded came, not shown
 *   shown     shown, its video not started
 *   started   shown, video.start came
 *   ended     hidden: terminal
 * ```
 */
@RunWith(RobolectricTestRunner::class)
class FrameStateTableTest {
    private val url = "https://frames.test/v2/frame/ad-1"
    private val origin = Origin.of(url)
    private val events = mutableListOf<FrameEvent>()

    @Before fun make() {
        Environment.context = ApplicationProvider.getApplicationContext()
        Environment.now = { 1_000_000L }
    }

    @After fun reset() = Environment.reset()

    private fun send(bridge: FrameBridge, type: String, fields: String = "") =
        bridge.receive("""{"adId":"ad-1","type":"$type"${if (fields.isEmpty()) "" else ", $fields"}}""", origin, true)

    private fun bridgeIn(column: String): FrameBridge {
        val bridge = FrameBridge(Environment.context!!, "ad-1", url, isVideo = true)
        bridge.onEvent = { events += it }
        if (column != "loading") send(bridge, "ad.loaded")
        if (column == "shown" || column == "started") bridge.show()
        if (column == "started") send(bridge, "video.start")
        if (column == "ended") bridge.hide()
        events.clear()
        return bridge
    }

    private fun outcome(column: String, type: String, fields: String): String {
        val bridge = bridgeIn(column)
        val before = bridge.state
        bridge.noteTouch()
        send(bridge, type, fields)
        val delivered = events.singleOrNull()?.let { it::class.simpleName } ?: "-"
        val ended = before != FramePhase.ENDED && bridge.state == FramePhase.ENDED
        return delivered + if (ended) "+end" else ""
    }

    @Test fun everyMessageInEveryState() {
        val columns = listOf("loading", "loaded", "shown", "started", "ended")
        val link = "\"url\": \"https://brand.test\""
        // message, its fields, then one cell per column above.
        val table = listOf(
            listOf("ad.loaded", "", "Loaded", "-", "-", "-", "-"),
            listOf("ad.click", link, "-", "-", "Click", "Click", "-"),
            listOf("ad.open", link, "-", "-", "Open", "Open", "-"),
            listOf("ad.close", "", "-", "-", "Close+end", "Close+end", "-"),
            listOf("video.skip", "", "-", "-", "Skip+end", "Skip+end", "-"),
            listOf("video.start", "", "-", "-", "Video", "Video", "-"),
            listOf("video.firstQuartile", "", "-", "-", "-", "Video", "-"),
            listOf("video.midpoint", "", "-", "-", "-", "Video", "-"),
            listOf("video.thirdQuartile", "", "-", "-", "-", "Video", "-"),
            listOf("video.pause", "", "-", "-", "-", "Video", "-"),
            listOf("video.resume", "", "-", "-", "-", "Video", "-"),
            listOf("video.mute", "", "-", "-", "Video", "Video", "-"),
            listOf("video.unmute", "", "-", "-", "Video", "Video", "-"),
            listOf("video.progress", "\"offset\": 1000", "-", "-", "-", "Progress", "-"),
            listOf("video.complete", "", "-", "-", "-", "Complete", "-"),
            listOf("ad.error", "", "Error+end", "Error+end", "Error+end", "Error+end", "-"),
            listOf("ad.unknown", "", "-", "-", "-", "-", "-"),
            listOf("video.unknown", "", "-", "-", "-", "-", "-"),
        )
        for (row in table) {
            val (type, fields) = row
            val actual = columns.map { outcome(it, type, fields) }
            assertEquals(type, row.drop(2), actual)
        }
    }
}
