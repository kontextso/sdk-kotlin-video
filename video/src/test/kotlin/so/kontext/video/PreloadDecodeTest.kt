package so.kontext.video

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import so.kontext.video.model.VideoAdFormat
import so.kontext.video.network.AdsService

class PreloadDecodeTest {
    private fun ad(format: String, extra: String = "") = """
        {
          "format": "$format", "bid_id": "b-$format", "ecpm": 12.4,
          "trigger": { "kind": "manual" }, $extra
          "creative": { "image": { "url": "https://c.test/i.jpg", "w": 600, "h": 200 } },
          "tracking": {
            "impression": "i", "viewable": "v", "not_viewable": "nv", "view_undetermined": "vu",
            "creative_view": "cv", "close": "c", "overlay_view_duration": "ovd",
            "click_through": "ct", "click_tracking": "ck"
          }
        }
    """.trimIndent()

    @Test
    fun `decodes every well-formed ad with its format, id and ecpm`() {
        val body = """{ "session_id": "s1", "ads": [${ad("INSTREAM_IMAGE_PAUSE")}, ${ad("INSTREAM_IMAGE_OVERLAY", "\"placement\": { \"position\": \"bottom\" },")}] }"""
        val response = AdsService.decode(body)
        assertEquals("s1", response.sessionId)
        assertEquals(listOf(VideoAdFormat.PAUSE, VideoAdFormat.OVERLAY), response.ads.map { it.format })
        assertEquals("b-INSTREAM_IMAGE_PAUSE", response.ads[0].adId)
        assertEquals(12.4, response.ads[0].ecpm)
        assertEquals("bottom", response.ads[1].placement?.position)
    }

    @Test
    fun `one malformed ad does not throw away the rest of the fill`() {
        val body = """{ "ads": [ { "format": "INSTREAM_IMAGE_PAUSE", "bid_id": "no-ecpm" }, ${ad("INSTREAM_VIDEO_INTERSTITIAL")} ] }"""
        val response = AdsService.decode(body)
        assertEquals(listOf("b-INSTREAM_VIDEO_INTERSTITIAL"), response.ads.map { it.adId })
    }

    @Test
    fun `an unknown format is skipped rather than failing the decode`() {
        val response = AdsService.decode("""{ "ads": [${ad("INSTREAM_VIDEO_SWIPE")}] }""")
        assertEquals(emptyList<String>(), response.ads.map { it.adId })
        assertNull(response.noFillReason)
    }

    @Test
    fun `per-format results and the whole-request no-fill reason are read`() {
        val body = """{ "ads": [], "results": [ { "format": "INSTREAM_IMAGE_PAUSE", "no_fill_reason": "frequency_capped" } ], "no_fill_reason": "content_unsafe" }"""
        val response = AdsService.decode(body)
        assertEquals("frequency_capped", response.results?.single()?.noFillReason)
        assertEquals("content_unsafe", response.noFillReason)
    }
}
