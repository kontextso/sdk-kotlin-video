package so.kontext.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import so.kontext.video.tracking.AdTracker

class AdTrackerTest {
    @Test
    fun `CACHEBUSTING becomes a fresh number and unknown macros become empty`() {
        val url = AdTracker.substituteMacros("https://a.test/view?cb=[CACHEBUSTING]&x=[UNKNOWN]")
        assertTrue(Regex("""https://a\.test/view\?cb=\d{8}&x=""").matches(url), url)
    }

    @Test
    fun `TIMESTAMP becomes a URL-encoded ISO-8601 time`() {
        val url = AdTracker.substituteMacros("https://a.test/e?t=[TIMESTAMP]")
        assertTrue(Regex("""https://a\.test/e\?t=\d{4}-\d{2}-\d{2}T\d{2}%3A\d{2}%3A\d{2}\.\d{3}Z""").matches(url), url)
    }

    @Test
    fun `event name comes from the e or event query parameter`() {
        assertEquals("viewable", AdTracker.eventName("https://a.test/impression/b/event?e=viewable&cb=1"))
        assertEquals("start", AdTracker.eventName("https://a.test/impression/b/vast?event=start"))
        assertNull(AdTracker.eventName("https://a.test/impression/b/view?cb=1"))
    }
}
