package so.kontext.video.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.payloads.AdsResponse
import so.kontext.video.internal.payloads.ConfigResponse
import so.kontext.video.internal.payloads.UNKNOWN_REASON

class PayloadsTest {
    @Test fun anOverlayDecodesWithEveryFact() {
        val ad = AdPayload.parse(Fixtures.json(Fixtures.overlay()))
        assertEquals("ad-1", ad.id)
        assertEquals(AdFormat.OVERLAY, ad.format)
        assertEquals(VideoAd.Price(8.0, "USD", VideoAd.Price.Precision.EXACT), ad.price)
        assertEquals(AdPayload.Position.BOTTOM, ad.position)
        assertEquals(listOf(AdPayload.Size(320, 80), AdPayload.Size(640, 160)), ad.sizes)
        assertEquals(6000L, ad.durationMillis)
        assertEquals(5, ad.tracking.size)
    }

    @Test fun anOverlayWithoutDurationStaysUntilEnded() {
        val ad = AdPayload.parse(Fixtures.json(Fixtures.overlay().replace(""", "duration": 6000""", "")))
        assertNull(ad.durationMillis)
    }

    @Test fun aFullScreenPauseAdHasNoSizes() {
        val ad = AdPayload.parse(Fixtures.json(Fixtures.pause(position = "fullscreen")))
        assertEquals(AdPayload.Position.FULLSCREEN, ad.position)
        assertTrue(ad.sizes.isEmpty())
    }

    @Test fun aLinearAdHasItsDurationAndSkipOffset() {
        val ad = AdPayload.parse(Fixtures.json(Fixtures.linear()))
        assertEquals(30_000L, ad.durationMillis)
        assertEquals(5_000L, ad.skipOffsetMillis)
        assertNull(ad.position)
    }

    @Test fun nonsenseIsRefused() {
        val refused = listOf(
            Fixtures.overlay(expires = 0),
            Fixtures.overlay(duration = "-1"),
            Fixtures.overlay(frameUrl = "file:///sdcard/ad.html"),
            Fixtures.overlay(frameUrl = "javascript://x/alert(1)"),
            Fixtures.overlay(frameUrl = "frames.test/no-scheme"),
            Fixtures.overlay(sizes = "[]"),
            Fixtures.overlay(sizes = """[{"width": 0, "height": 80}]"""),
            Fixtures.overlay(sizes = """[{"width": 20000, "height": 80}]"""),
            Fixtures.overlay(id = ""),
            Fixtures.overlay().replace("\"manual\"", "\"timed\""),
        )
        for (text in refused) assertThrows<ApiException.Decoding>(text) { AdPayload.parse(Fixtures.json(text)) }
    }

    @Test fun aResponseGivesEachFormatItsAdOrItsNoFillReason() {
        val response = AdsResponse.parse(
            Fixtures.ads(
                Fixtures.entry("overlay", Fixtures.overlay()),
                Fixtures.entry("pause", noFill = "frequencyCap"),
                Fixtures.entry("linear", noFill = "somethingNew"),
            ),
        )
        assertEquals("ad-1", response.result(AdFormat.OVERLAY)?.ads?.single()?.id)
        assertEquals(NoFillReason.FrequencyCap, response.result(AdFormat.PAUSE)?.noFillReason)
        assertEquals(NoFillReason.Other("somethingNew"), response.result(AdFormat.LINEAR)?.noFillReason)
    }

    @Test fun aNoFillWithoutAReasonIsUnknown() {
        val response = AdsResponse.parse(Fixtures.ads(Fixtures.entry("pause")))
        assertEquals(NoFillReason.Unknown, response.result(AdFormat.PAUSE)?.noFillReason)
        assertNull(response.result(AdFormat.OVERLAY), "a format the server did not answer for")
    }

    @Test fun aFormatListedTwiceIsRefusedAndAnAdUnderTheWrongFormatDropped() {
        assertThrows<ApiException.Decoding> {
            AdsResponse.parse(Fixtures.ads(Fixtures.entry("pause"), Fixtures.entry("pause")))
        }
        val pause = AdsResponse.parse(Fixtures.ads(Fixtures.entry("pause", Fixtures.overlay()))).result(AdFormat.PAUSE)!!
        assertTrue(pause.ads.isEmpty())
        assertEquals(NoFillReason.Unknown, pause.noFillReason)
    }

    @Test fun theKillSwitchCarriesItsReason() {
        val response = AdsResponse.parse(Fixtures.json("""{"enabled": false, "disabledReason": "publisherPaused"}"""))
        assertEquals("publisherPaused", (response as AdsResponse.Disabled).reason)
        val unknown = AdsResponse.parse(Fixtures.json("""{"enabled": false}"""))
        assertEquals(UNKNOWN_REASON, (unknown as AdsResponse.Disabled).reason)
    }

    @Test fun configDefaultsTheReportsToOff() {
        assertEquals(ConfigResponse.Enabled(reportDebug = false, reportErrors = false), ConfigResponse.parse(Fixtures.json("""{"enabled": true}""")))
        assertEquals(
            ConfigResponse.Enabled(reportDebug = true, reportErrors = true),
            ConfigResponse.parse(Fixtures.json("""{"enabled": true, "reportDebug": true, "reportErrors": true}""")),
        )
        assertEquals(ConfigResponse.Disabled("x"), ConfigResponse.parse(Fixtures.json("""{"enabled": false, "disabledReason": "x"}""")))
        assertThrows<ApiException.Decoding> { ConfigResponse.parse(Fixtures.json("""{"reportDebug": true}""")) }
    }

    @Test fun theErrorBodyIsReadLeniently() {
        val items = ApiClient.parseErrors("""{"errors": [{"status": "400", "title": "Bad token", "code": 7}]}""")
        assertEquals("Bad token", items.single().summary)
        assertNull(items.single().code, "a code that is not a string")
        assertTrue(ApiClient.parseErrors("""{"errors": ["text"]}""").isEmpty())
        assertTrue(ApiClient.parseErrors("not json").isEmpty())
    }
}
