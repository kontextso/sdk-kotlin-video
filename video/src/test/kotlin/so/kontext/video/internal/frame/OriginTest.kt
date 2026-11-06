package so.kontext.video.internal.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A page's origin, as the bridge compares them: the scheme, host and port read from a URL. */
@RunWith(RobolectricTestRunner::class)
class OriginTest {
    @Test fun aFramePageOnAHostWithAnUnderscoreHasItsOrigin() {
        assertEquals(Origin("http", "ads_server", 3002), Origin.of("http://ads_server:3002/v2/frame/ad-1"))
        assertEquals("http://ads_server:3002", Origin.of("http://ads_server:3002/v2/frame/ad-1")?.rule)
    }

    @Test fun anOriginIsItsSchemeHostAndPort() {
        assertEquals(Origin("https", "frames.test", 443), Origin.of("HTTPS://Frames.Test/path"))
        assertEquals("https://frames.test:8443", Origin.of("https://frames.test:8443/x")!!.rule)
        assertNull(Origin.of("file:///x"))
    }

    @Test fun originsSpellOutTheDefaultPort() {
        assertEquals("https://frames.test", Origin.of("https://Frames.Test/x")!!.rule)
        assertEquals("http://frames.test", Origin.of("http://frames.test:80/")!!.rule)
        assertEquals("https://frames.test:8443", Origin.of("https://frames.test:8443/")!!.rule)
        assertEquals(-1, Origin.of("ws://frames.test/")!!.port)
        assertNull("no scheme", Origin.of("frames.test/x"))
        assertNull("no host", Origin.of("mailto:someone@test"))
        assertNull("an empty host", Origin.of("https:///path"))
    }
}
