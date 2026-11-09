package so.kontext.video.internal.payloads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException.NoFillReason
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.network.ApiException

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
        assertEquals(ConfigResponse.Enabled(reportDebug = false, reportErrors = false, errorBatchIntervalMillis = 100, debugBatchIntervalMillis = 100), ConfigResponse.parse(Fixtures.json("""{"enabled": true}""")))
        assertEquals(
            ConfigResponse.Enabled(reportDebug = true, reportErrors = true, errorBatchIntervalMillis = 100, debugBatchIntervalMillis = 100),
            ConfigResponse.parse(Fixtures.json("""{"enabled": true, "reportDebug": true, "reportErrors": true}""")),
        )
        assertEquals(ConfigResponse.Disabled("x"), ConfigResponse.parse(Fixtures.json("""{"enabled": false, "disabledReason": "x"}""")))
        assertThrows<ApiException.Decoding> { ConfigResponse.parse(Fixtures.json("""{"reportDebug": true}""")) }
    }

    @Test fun theErrorBodyIsReadLeniently() {
        val items = ErrorResponse.parse("""{"errors": [{"status": "400", "title": "Bad token", "code": 7}]}""")
        assertEquals("Bad token", items.single().summary)
        assertNull(items.single().code, "a code that is not a string")
        assertTrue(ErrorResponse.parse("""{"errors": ["text"]}""").isEmpty())
        assertTrue(ErrorResponse.parse("not json").isEmpty())
    }

    @Test fun anErrorBodyNestedTooDeepIsNoneAndNeverParsed() {
        val deep = "{\"errors\": " + "[".repeat(100_000) + "]".repeat(100_000) + "}"
        assertTrue(ErrorResponse.parse(deep).isEmpty(), "past JsonParser.MAX_DEPTH: no StackOverflowError, no items")
        assertTrue(ErrorResponse.parse("[".repeat(100_000)).isEmpty())
    }

    @Test fun anEmptyOrBlankReasonIsUnknown() {
        for (reason in listOf("\"\"", "\"   \"")) {
            val config = ConfigResponse.parse(Fixtures.json("""{"enabled": false, "disabledReason": $reason}"""))
            assertEquals(UNKNOWN_REASON, (config as ConfigResponse.Disabled).reason)
            val ads = AdsResponse.parse(Fixtures.json("""{"enabled": false, "disabledReason": $reason}"""))
            assertEquals(UNKNOWN_REASON, (ads as AdsResponse.Disabled).reason)
        }
        assertEquals(NoFillReason.Unknown, so.kontext.video.internal.payloads.noFillReasonFrom(""))
        assertEquals(NoFillReason.Unknown, so.kontext.video.internal.payloads.noFillReasonFrom("  "))
    }

    @Test fun aSizeOutOfRangeIsReportedAtItsOwnKey() {
        for ((sizes, key) in listOf("""[{"width": 320, "height": 0}]""" to "sizes.height", """[{"width": 0, "height": 50}]""" to "sizes.width", """[{"width": 320, "height": 10001}]""" to "sizes.height")) {
            val error = assertThrows<ApiException.Decoding> { AdPayload.parse(Fixtures.json(Fixtures.overlay(sizes = sizes))) }
            assertEquals(key, (error.cause as AdPayload.MalformedField).path, sizes)
        }
    }

    @Test fun thePayloadsLastRefusals() {
        val refused = listOf(
            Fixtures.overlay().replace("\"creative\": \"image\"", "\"creative\": \"audio\""),
            Fixtures.overlay().replace("\"precision\": \"exact\"", "\"precision\": \"guess\""),
            Fixtures.overlay().replace("\"position\": \"bottom\"", "\"position\": \"left\""),
            Fixtures.pause().replace("\"position\": \"top\"", "\"position\": \"middle\""),
            Fixtures.overlay().replace("\"kind\": \"frame\"", "\"kind\": \"vast\""),
            Fixtures.linear().replace("\"duration\": 30000, ", ""),
            Fixtures.linear().replace("\"skipOffset\": 5000", "\"skipOffset\": -1"),
            Fixtures.overlay().replace("\"format\": \"overlay\"", "\"format\": \"banner\""),
            Fixtures.overlay().replace("{\"kind\": \"manual\"}", "{\"kind\": \"window\", \"from\": 1}"),
        )
        for (text in refused) {
            try {
                AdPayload.parse(Fixtures.json(text))
                org.junit.Assert.fail("accepted: $text")
            } catch (_: ApiException.Decoding) {
            }
        }
        try {
            AdPayload.parse(Fixtures.json(Fixtures.overlay().replace("{\"kind\": \"manual\"}", "{\"kind\": \"window\", \"from\": 1, \"to\": 2}")))
            org.junit.Assert.fail("a window trigger is not supported yet: the ad is dropped")
        } catch (_: ApiException.Decoding) {
        }
    }
}
