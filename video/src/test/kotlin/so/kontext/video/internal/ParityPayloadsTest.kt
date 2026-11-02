package so.kontext.video.internal

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import so.kontext.video.AdFormat
import so.kontext.video.Configuration
import so.kontext.video.Content
import so.kontext.video.KontextVideo
import so.kontext.video.NoFillReason
import so.kontext.video.Regulatory
import so.kontext.video.SdkTest
import so.kontext.video.User
import so.kontext.video.VideoAd
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAds
import so.kontext.video.internal.device.Consent
import so.kontext.video.internal.device.DeviceFacts
import so.kontext.video.internal.network.ApiClient
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.rendering.Tracker
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.wire.AdPayload
import so.kontext.video.internal.wire.AdsRequest
import so.kontext.video.internal.wire.AdsResponse
import so.kontext.video.internal.wire.ConfigResponse
import so.kontext.video.internal.wire.UNKNOWN_REASON
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert as JUnit4
import org.junit.Test as JUnit4Test

/** The Swift suite's payload, error-body and model rules, checked on this side. */
class ParityPayloadsTest {
    private fun parse(text: String) = AdPayload.parse(Fixtures.json(text))

    private fun ads(text: String) = AdsResponse.parse(Fixtures.json(text))

    private fun config(text: String) = ConfigResponse.parse(Fixtures.json(text))

    @Test fun aConfigWithoutAReasonIsDisabledForAnUnknownOne() {
        assertEquals(ConfigResponse.Disabled(UNKNOWN_REASON), config("""{"enabled": false}"""))
    }

    @Test fun aConfigAnswerNeedsABooleanEnabledAndToleratesTheRest() {
        for (body in listOf("{}", """{"enabled": null}""", """{"enabled": "true"}""", """{"enabled": 1}""")) {
            assertThrows<ApiException.Decoding>(body) { config(body) }
        }
        for (flag in listOf("null", "\"yes\"", "1")) {
            assertEquals(
                ConfigResponse.Enabled(reportDebug = false, reportErrors = false),
                config("""{"enabled": true, "reportDebug": $flag, "reportErrors": $flag}"""),
                "a malformed flag is off: $flag",
            )
        }
        for (reason in listOf("null", "42")) {
            assertEquals(ConfigResponse.Disabled(UNKNOWN_REASON), config("""{"enabled": false, "disabledReason": $reason}"""), reason)
        }
        assertEquals(ConfigResponse.Disabled("aReasonFromTheFuture"), config("""{"enabled": false, "disabledReason": "aReasonFromTheFuture"}"""))
    }

    @Test fun theTimedModeFieldsAreIgnoredOnAFilledAndANoFillEntry() {
        val response = ads(
            """{"enabled": true, "formats": [
                {"format": "pause", "ads": [${Fixtures.pause()}], "nextRequestAt": 25000},
                {"format": "linear", "ads": [], "noFillReason": "temporaryError", "nextRequestAt": 38000}]}""",
        )
        assertEquals("ad-2", response.result(AdFormat.PAUSE)?.ads?.single()?.id)
        assertNull(response.result(AdFormat.PAUSE)?.noFillReason)
        assertEquals(NoFillReason.TemporaryError, response.result(AdFormat.LINEAR)?.noFillReason)
        assertTrue(response.result(AdFormat.LINEAR)!!.ads.isEmpty())
    }

    @Test fun anUnknownNoFillReasonKeepsItsWireNameForLogs() {
        val reason = ads("""{"enabled": true, "formats": [${Fixtures.entry("pause", noFill = "brandNew")}]}""").result(AdFormat.PAUSE)?.noFillReason
        assertEquals(NoFillReason.Other("brandNew"), reason)
        assertEquals("brandNew", reason.toString())
    }

    @Test fun reasonsOfTheWrongTypeReadUnknown() {
        val response = ads("""{"enabled": true, "formats": [{"format": "pause", "ads": [], "noFillReason": 7}]}""")
        assertEquals(NoFillReason.Unknown, response.result(AdFormat.PAUSE)?.noFillReason, "the answer is understood, the reason is not")
        assertEquals(UNKNOWN_REASON, (ads("""{"enabled": false, "disabledReason": ["x"]}""") as AdsResponse.Disabled).reason)
    }

    @Test fun anAdsAnswerWithoutEnabledOrFormatsOrAFormatOrAdsIsRefused() {
        val refused = listOf(
            """{"formats": []}""",
            """{"enabled": true}""",
            """{"enabled": true, "formats": [{"ads": []}]}""",
            """{"enabled": true, "formats": [{"format": 7, "ads": []}]}""",
            """{"enabled": true, "formats": [{"format": "pause"}]}""",
            """{"enabled": true, "formats": ["pause"]}""",
        )
        for (body in refused) assertThrows<ApiException.Decoding>(body) { ads(body) }
    }

    @Test fun aResultForAFormatTheSdkDoesNotKnowIsIgnored() {
        val response = ads(
            """{"enabled": true, "formats": [
                {"format": "menu", "ads": [{"id": "m-1", "format": "menu", "creative": "native"}]},
                ${Fixtures.entry("pause", Fixtures.pause())}]}""",
        )
        assertEquals("ad-2", response.result(AdFormat.PAUSE)?.ads?.single()?.id)
        assertEquals(1, (response as AdsResponse.Formats).results.size)
    }

    @Test fun aMalformedAdIsDroppedAndLeavesItsFormatANoFillWhenNothingIsLeft() {
        // A value of a later contract, a broken price, a missing field: each fails its ad only.
        val newCreative = Fixtures.overlay(id = "ad-8").replace("\"creative\": \"image\"", "\"creative\": \"playable\"")
        val badPrice = Fixtures.pause(id = "ad-9").replace("5.5", "-1")
        val response = ads(
            """{"enabled": true, "formats": [
                ${Fixtures.entry("overlay", newCreative)},
                ${Fixtures.entry("pause", badPrice, Fixtures.pause(), noFill = "frequencyCap")},
                ${Fixtures.entry("linear", Fixtures.linear())}]}""",
        )
        val overlay = response.result(AdFormat.OVERLAY)!!
        assertTrue(overlay.ads.isEmpty())
        assertEquals(NoFillReason.Unknown, overlay.noFillReason, "ads came and none could be read: no reason of the server's")
        assertEquals(1, overlay.dropped.size)
        val pause = response.result(AdFormat.PAUSE)!!
        assertEquals("ad-2", pause.ads.single().id, "the valid ad of the format stays")
        assertEquals(1, pause.dropped.size)
        assertEquals("ad-3", response.result(AdFormat.LINEAR)?.ads?.single()?.id, "another format is untouched")
        assertTrue(response.result(AdFormat.LINEAR)!!.dropped.isEmpty())
    }

    @Test fun anAdsAnswerEdges() {
        assertTrue(ads("""{"enabled": false, "disabledReason": "publisherDisabled", "formats": "junk"}""") is AdsResponse.Disabled, "disabled wins, formats unread")
        val filled = ads("""{"enabled": true, "formats": [${Fixtures.entry("pause", Fixtures.pause(), noFill = "frequencyCap")}]}""")
        assertNull(filled.result(AdFormat.PAUSE)?.noFillReason, "a filled entry ignores a contradictory reason")
        assertEquals(1, filled.result(AdFormat.PAUSE)?.ads?.size)
        val broken = Fixtures.pause(id = "ad-9").replace(""""sizes": [{"width": 300, "height": 250, "scalable": false}]""", """"sizes": []""")
        val withBroken = ads("""{"enabled": true, "formats": [${Fixtures.entry("pause", Fixtures.pause(), broken)}]}""").result(AdFormat.PAUSE)!!
        assertEquals(listOf("ad-2"), withBroken.ads.map { it.id }, "a malformed second ad is dropped")
        // An ad filed under the wrong format (its own `format` is not its entry's) is a malformed ad: it alone is
        // dropped, as a lenient ad is, and the rest of the answer counts.
        val withOther = ads("""{"enabled": true, "formats": [${Fixtures.entry("pause", Fixtures.pause(), Fixtures.overlay())}]}""").result(AdFormat.PAUSE)!!
        assertEquals(listOf("ad-2"), withOther.ads.map { it.id }, "a second ad of another format is dropped")
        assertEquals(1, withOther.dropped.size)
        assertNull(withOther.noFillReason)
        val onlyOther = ads(
            """{"enabled": true, "formats": [
                ${Fixtures.entry("pause", Fixtures.overlay(), noFill = "frequencyCap")},
                ${Fixtures.entry("linear", Fixtures.linear())}]}""",
        )
        val misfiled = onlyOther.result(AdFormat.PAUSE)!!
        assertTrue(misfiled.ads.isEmpty(), "the only ad, filed under the wrong format: dropped")
        assertEquals(NoFillReason.Unknown, misfiled.noFillReason, "a no-fill of reason unknown, whatever the entry says")
        assertTrue("the result is PAUSE" in misfiled.dropped.single(), misfiled.dropped.single())
        assertNull(onlyOther.result(AdFormat.OVERLAY), "nor is it filed under its own format")
        assertEquals("ad-3", onlyOther.result(AdFormat.LINEAR)?.ads?.single()?.id, "another format is untouched")
    }

    @Test fun aPauseAdDecodesWithEveryFact() {
        val ad = parse(Fixtures.pause())
        assertEquals("ad-2", ad.id)
        assertEquals(AdFormat.PAUSE, ad.format)
        assertEquals(AdPayload.Creative.IMAGE, ad.creative)
        assertEquals(600_000L, ad.expiresMillis)
        assertEquals(VideoAd.Price(5.5, "EUR", VideoAd.Price.Precision.ESTIMATED), ad.price)
        assertEquals("https://frames.test/v2/frame/ad-2", ad.frameUrl)
        assertEquals(AdPayload.Position.TOP, ad.position)
        assertEquals(listOf(AdPayload.Size(300, 250, scalable = false)), ad.sizes)
        assertNull(ad.durationMillis)
        assertNull(ad.skipOffsetMillis)
    }

    @Test fun aLinearAdIsAVideoAndWithoutSkipOffsetHasNoCloseBeforeTheEnd() {
        assertEquals(AdPayload.Creative.VIDEO, parse(Fixtures.linear()).creative)
        assertNull(parse(Fixtures.linear().replace(""", "skipOffset": 5000""", "")).skipOffsetMillis)
        assertEquals(0L, parse(Fixtures.linear().replace(""""skipOffset": 5000""", """"skipOffset": 0""")).skipOffsetMillis, "a Close from the start")
    }

    @Test fun anHtmlCreativeDecodes() {
        assertEquals(AdPayload.Creative.HTML, parse(Fixtures.overlay().replace(""""creative": "image"""", """"creative": "html"""")).creative)
    }

    @Test fun anExpiryBelowZeroIsRefusedAndAZeroOverlayDurationIsNot() {
        assertThrows<ApiException.Decoding> { parse(Fixtures.overlay(expires = -5)) }
        assertEquals(0L, parse(Fixtures.overlay(duration = "0")).durationMillis)
    }

    @Test fun aSideAboveTenThousandIsRefusedAndTenThousandIsNot() {
        for (side in listOf("10001", Long.MAX_VALUE.toString())) {
            assertThrows<ApiException.Decoding>(side) { parse(Fixtures.overlay(sizes = """[{"width": $side, "height": 250}]""")) }
        }
        assertEquals(10_000, parse(Fixtures.overlay(sizes = """[{"width": 10000, "height": 250}]""")).sizes.single().width)
    }

    @Test fun aPauseAdAtTheTopNeedsItsSizes() {
        assertThrows<ApiException.Decoding> { parse(Fixtures.pause().replace(""", "sizes": [{"width": 300, "height": 250, "scalable": false}]""", "")) }
    }

    @Test fun fractionalMillisecondsAreRoundedLikeTheSwiftSdk() {
        val overlay = parse(Fixtures.overlay(duration = "3000.6").replace("\"expires\": 600000", "\"expires\": 600000.4"))
        assertEquals(3001L, overlay.durationMillis)
        assertEquals(600_000L, overlay.expiresMillis)
        val linear = parse(Fixtures.linear().replace("30000, \"skipOffset\": 5000", "29999.5, \"skipOffset\": 5000.49"))
        assertEquals(30_000L, linear.durationMillis)
        assertEquals(5_000L, linear.skipOffsetMillis)
        val window = Fixtures.overlay().replace("{\"kind\": \"manual\"}", "{\"kind\": \"window\", \"from\": 1000.25, \"to\": 9000.5}")
        assertEquals("ad-1", parse(window).id, "a fractional window decodes")
        // Out of range, not finite or negative stays invalid.
        for (refused in listOf(
            Fixtures.overlay(duration = "-0.6"),
            Fixtures.overlay(duration = "315360000001"),
            Fixtures.overlay(duration = "1e400"),
            Fixtures.overlay().replace("\"expires\": 600000", "\"expires\": 0.4"),
            Fixtures.overlay().replace("\"expires\": 600000", "\"expires\": 3.2e11"),
            Fixtures.linear().replace("\"skipOffset\": 5000", "\"skipOffset\": -1.5"),
            window.replace("9000.5", "1e400"),
        )) {
            assertThrows<ApiException.Decoding>(refused) { parse(refused) }
        }
    }

    @Test fun aProgressOffsetIsAnyFiniteNonNegativeNumberRoundedToTheMillisecond() {
        fun progress(index: Int, offset: String) = """{"event": "progress", "url": "https://t.test/p?n=$index", "offset": $offset}"""
        val entries = listOf("4957.59", "4957.4", "5000.0", "0", "0.4", "-1", "-0.5", "1e400", "1e300", "\"5000\"", "{}", "315360000001")
        val ad = parse(Fixtures.overlay(tracking = entries.mapIndexed(::progress).joinToString(prefix = "[", postfix = "]")))
        assertEquals(listOf(4958L, 4957L, 5000L, 0L, 0L), ad.tracking.map { it.offsetMillis }, "rounded; the rest drop their entry only")
        val absent = parse(Fixtures.overlay(tracking = """[{"event": "impression", "url": "https://t.test/i", "offset": null}]"""))
        assertEquals(1, absent.tracking.size, "an absent offset is no offset")
        // A fractional checkpoint fires once the video passes it.
        val sent = mutableListOf<String>()
        Environment.sendBeacon = { url, _ -> sent += url }
        try {
            Tracker(ad.id, ad.tracking).fireProgress(5_000)
            assertEquals((0..4).map { "https://t.test/p?n=$it" }, sent.sorted())
        } finally {
            Environment.sendBeacon = Tracker::get
        }
    }

    @Test fun aScalableFlagOfTheWrongTypeIsAMalformedSize() {
        assertThrows<ApiException.Decoding> { parse(Fixtures.overlay(sizes = """[{"width": 300, "height": 250, "scalable": "no"}]""")) }
        assertTrue(parse(Fixtures.overlay(sizes = """[{"width": 300, "height": 250, "scalable": null}]""")).sizes.single().scalable, "null is absent")
    }

    @Test fun theFrameUrlMustBeAbsoluteWithAHostAndIsKeptAsGiven() {
        for (ok in listOf("https://server.kontext.so/frame/ad-1", "http://localhost:5175/frame/ad-1", "HTTPS://frames.test:65535/a", "https://frames.test:1/a")) {
            assertEquals(ok, parse(Fixtures.overlay(frameUrl = ok)).frameUrl)
        }
        val refused = listOf(
            "frame/ad-1", "/frame/ad-1", "file:///etc/passwd", "file://localhost/etc/passwd", "javascript:alert(1)", "data:text/html,hi",
            "blob:https://x/y", "about:blank", "FILE://localhost/etc/passwd", "JavaScript://x/alert(1)", "Data://x/y",
        )
        for (bad in refused) assertThrows<ApiException.Decoding>(bad) { parse(Fixtures.overlay(frameUrl = bad)) }
    }

    /** The bridge's origin rule takes a web origin only: any other scheme or port would make the WebView throw. */
    @Test fun theFrameUrlIsAWebPageWithAValidPort() {
        val refused = listOf(
            "ftp://frames.test/a", "myapp://frames.test/a", "ws://frames.test/a", "kontext-test://frames/frame/ad-1",
            "https://frames.test:99999/a", "https://frames.test:0/a", "https://frames.test:65536/a", "https:///a", "https://fr ames.test/a",
        )
        for (bad in refused) assertThrows<ApiException.Decoding>(bad) { parse(Fixtures.overlay(frameUrl = bad)) }
    }

    @Test fun aPriceIsAFiniteCpmNotNegativeInAnIsoCurrency() {
        fun overlay(value: String, currency: String = "USD") =
            Fixtures.overlay().replace(""""value": 8.0, "currency": "USD"""", """"value": $value, "currency": "$currency"""")
        for (value in listOf("1e400", "-1e400", "-1", "-0.01", "\"NaN\"", "\"Infinity\"", "NaN", "Infinity")) {
            assertThrows<ApiException>(value) { parse(overlay(value)) }
        }
        for (currency in listOf("usd", "US", "EURO", "", "U5D")) {
            assertThrows<ApiException.Decoding>(currency) { parse(overlay("8.0", currency)) }
        }
        assertEquals(0.0, parse(overlay("0")).price.value)
        assertEquals(1e300, parse(overlay("1e300")).price.value)
        assertEquals("EUR", parse(overlay("8.0", "EUR")).price.currency)
    }

    @Test fun trackingEntriesKeepOffsetAndPart() {
        val tracking = parse(Fixtures.overlay()).tracking
        val progress = tracking.first { it.event == "progress" }
        assertEquals(5_000L, progress.offsetMillis)
        assertNull(progress.part)
        assertEquals("endCard", tracking.single { it.event == "click" && it.part != null }.part)
        assertEquals(2, tracking.count { it.event == "click" }, "the creative's and the end card's")
    }

    @Test fun fieldsTheSdkNeverReadsDoNotFailTheAd() {
        val extras = listOf(
            """"reward": {"label": "coins", "amount": 50}""",
            """"placementId": "legacy"""",
            """"vastUrl": "https://ads.test/v2/video/ads/ad-3/vast"""",
            """"attribution": {"skan": "broken", "aak": {"jws": "j"}}""",
            """"attribution": "nonsense"""",
        )
        for (extra in extras) {
            assertEquals("ad-3", parse(Fixtures.linear().replaceFirst("{", "{$extra, ")).id, extra)
        }
    }

    @Test fun anErrorItemIsSummarizedByTitleThenDetailThenCode() {
        fun summaries(items: String) = ApiClient.parseErrors("""{"errors": $items}""").map { it.summary }
        assertEquals(listOf("T"), summaries("""[{"title": "T", "detail": "D", "code": "C"}]"""))
        assertEquals(listOf("D"), summaries("""[{"detail": "D", "code": "C"}]"""))
        assertEquals(listOf("C"), summaries("""[{"code": "C"}]"""))
        assertEquals(listOf(""), summaries("""[{"title": "", "detail": "D"}]"""), "an empty title as it is")
        assertEquals(listOf(null), summaries("[{}]"))
        assertTrue(summaries("""[{"title": "T"}, "junk"]""").isEmpty(), "a non-object entry fails the body")
    }

    @Test fun anErrorLineSaysWhatEachItemCanSay() {
        val codeOnly = ApiClient.parseErrors("""{"errors": [{"code": "invalidRequest"}]}""")
        assertEquals("invalidRequest", codeOnly.single().code)
        assertNull(codeOnly.single().title)
        assertEquals("HTTP 400: invalidRequest", ApiException.Http(400, codeOnly).message)
        val mixed = ApiClient.parseErrors("""{"errors": [{"title": "Something broke"}, {"code": 42, "title": ["not", "a", "string"]}, {"code": "rateLimited"}]}""")
        assertEquals(listOf(null, null, "rateLimited"), mixed.map { it.code }, "nothing dropped; a missing or malformed code is null")
        assertEquals("HTTP 500: Something broke; rateLimited", ApiException.Http(500, mixed).message)
        assertEquals("HTTP 500", ApiException.Http(500, listOf(mixed[1])).message)
        val full = ApiClient.parseErrors("""{"errors": [{"status": "429", "code": "rateLimited", "title": "slow down"}]}""").single()
        assertEquals("rateLimited", full.code)
        assertNull(full.detail)
    }

    @Test fun contentEncodesEveryFieldAndNothingElse() {
        val content = Content("100001-6", "pt-BR") {
            mediaRating = Content.Rating.EXPLICIT
            url = "https://p.test/ep/6"
            title = "Episode 6"
            categories = listOf("640")
            description = "d"
            durationMillis = 92_400
            episodeNumber = 6
            keywords = listOf("love")
            genre = "romance"
            series = Content.Series("100001") {
                title = "S"
                description = "sd"
                episodeCount = 40
            }
        }
        val expected = """
            {"id": "100001-6", "language": "pt-BR", "mediaRating": "explicit", "url": "https://p.test/ep/6", "title": "Episode 6",
             "categories": ["640"], "description": "d", "duration": 92400, "episodeNumber": 6, "keywords": ["love"], "genre": "romance",
             "series": {"id": "100001", "title": "S", "description": "sd", "episodeCount": 40}}
        """
        assertEquals(Json.parseToJsonElement(expected), AdsRequest.content(content))
    }

    @Test fun numbersBelowOneAndASeriesWithoutAnIdAreLeftOut() {
        for (number in listOf(0, -3)) {
            val json = AdsRequest.content(
                Content("v", "en") {
                    episodeNumber = number
                    durationMillis = number.toLong()
                    series = Content.Series("s") { episodeCount = number }
                },
            )
            assertEquals(Json.parseToJsonElement("""{"id": "v", "language": "en", "series": {"id": "s"}}"""), json, "$number")
        }
        assertNull(AdsRequest.content(Content("v", "en") { series = Content.Series("") { title = "T" } })["series"])
        val valid = AdsRequest.content(
            Content("v", "en") {
                episodeNumber = 1
                series = Content.Series("s") { episodeCount = 1 }
            },
        )
        assertEquals("1", valid["episodeNumber"]!!.jsonPrimitive.content)
        assertEquals("1", valid["series"]!!.jsonObject["episodeCount"]!!.jsonPrimitive.content)
    }

    @Test fun theProblemsNameWhatThePublisherPassedWrong() {
        val good = Content("v", "en") {
            durationMillis = 92_000
            episodeNumber = 6
        }
        assertTrue(good.missingRequired.isEmpty() && good.leftOut.isEmpty())
        val bad = Content("", "") {
            durationMillis = -1
            episodeNumber = 0
            series = Content.Series("") { episodeCount = 0 }
        }
        assertEquals(listOf("id is empty", "language is empty"), bad.missingRequired)
        assertEquals(
            listOf("durationMillis -1 left out", "episodeNumber 0 left out", "series left out, its id is empty", "series.episodeCount 0 left out"),
            bad.leftOut,
        )
        val blank = Content(" ", " ") { series = Content.Series(" ") }
        assertTrue(blank.missingRequired.isEmpty() && blank.leftOut.isEmpty(), "the schema's rule is non-empty")
    }

    @Test fun aUserIsSentExactlyAsGivenAndOnlyWhatIsSet() {
        val user = User {
            id = "U-42"
            email = "  Viewer.Name@Example.COM "
            emailHash = "AbC123=="
            keywords = listOf("Romance", " CEO ")
        }
        assertEquals(
            Json.parseToJsonElement("""{"id": "U-42", "email": "  Viewer.Name@Example.COM ", "emailHash": "AbC123==", "keywords": ["Romance", " CEO "]}"""),
            AdsRequest.user(user),
        )
        assertEquals(
            Json.parseToJsonElement("""{"id": "u1", "keywords": []}"""),
            AdsRequest.user(
                User {
                    id = "u1"
                    keywords = emptyList()
                },
            ),
        )
        assertEquals(setOf("emailHash"), AdsRequest.user(User { emailHash = "abc" }).keys)
        val empty = User {
            id = ""
            email = ""
            emailHash = ""
        }
        assertTrue(AdsRequest.user(empty).isEmpty())
        assertEquals(listOf("id", "email", "emailHash"), empty.emptyFields)
        assertTrue(AdsRequest.user(User {}).isEmpty())
        assertNotEquals(User { id = "u1" }, User { id = "u2" })
    }

    @Test fun regulatoryEncodesEveryFieldAndOmitsWhatIsUnset() {
        val full = Regulatory {
            gdpr = true
            gdprConsent = "TC"
            coppa = false
            gpp = "GPP"
            gppSid = listOf(2, 7)
            usPrivacy = "1YNN"
        }
        assertEquals(
            Json.parseToJsonElement("""{"gdpr": true, "gdprConsent": "TC", "coppa": false, "gpp": "GPP", "gppSid": [2, 7], "usPrivacy": "1YNN"}"""),
            AdsRequest.regulatory(full),
        )
        assertEquals(Json.parseToJsonElement("""{"coppa": true}"""), AdsRequest.regulatory(Regulatory { coppa = true }))
    }

    @Test fun theDefaultServerIsProductionAndTheLogLevelsAreOrdered() {
        assertEquals("https://server.megabrain.co", Configuration.DEFAULT_AD_SERVER_URL)
        val levels = Configuration.LogLevel.entries
        assertEquals(listOf(Configuration.LogLevel.NONE, Configuration.LogLevel.ERROR, Configuration.LogLevel.WARNING, Configuration.LogLevel.INFO, Configuration.LogLevel.DEBUG), levels)
        assertTrue(levels.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun theSdkCarriesItsNameAndAReleaseVersion() {
        assertTrue(Regex("""\A(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\z""").matches(KontextVideo.VERSION), KontextVideo.VERSION)
        val sdk = DeviceFacts.sdk()
        assertEquals("sdk-kotlin-video", sdk["name"]!!.jsonPrimitive.content)
        assertEquals(KontextVideo.VERSION, sdk["version"]!!.jsonPrimitive.content)
    }

    @Test fun everyExceptionReadsAsASentence() {
        val cases = listOf(
            VideoAdException.NotInitialized() to "KontextVideo.initialize() was not called",
            VideoAdException.InitializationFailed() to "/config failed, and trying it again failed too; no ads until it succeeds",
            VideoAdException.Destroyed() to "the VideoAds was destroyed",
            VideoAdException.Disabled("publisherDisabled") to "the SDK is disabled by the server (publisherDisabled)",
            VideoAdException.NoFill(NoFillReason.NoAdMatch) to "no fill (noAdMatch)",
            VideoAdException.NoFill(NoFillReason.Other("brandNew")) to "no fill (brandNew)",
            VideoAdException.AnotherAdShowing() to "another ad is on screen; show this one after it ended",
            VideoAdException.CreativeFailed(400) to "the creative failed (VAST error 400)",
            VideoAdException.AlreadyShown() to "the ad was already shown",
            VideoAdException.Invalidated(VideoAd.InvalidationReason.Expired) to "the ad ended before it was shown (Expired)",
            VideoAdException.NotLoaded() to "the ad is not loaded yet; call load() first",
            VideoAdException.ContainerTooSmall(150, 150) to "the container is too small for the ad; at least 150×150 px are needed",
            VideoAdException.PresenterUnavailable() to "nothing on screen can hold the ad now",
            VideoAdException.WrongPresentation() to
                "this call does not fit the ad's format (show(container) for overlay and pause, show(activity) for linear; dismiss() only for overlay and pause)",
        )
        for ((error, expected) in cases) assertEquals(expected, error.message)
        val request = VideoAdException.Request(ApiException.Http(500, emptyList()))
        assertEquals("request failed: HTTP 500", request.message, "the underlying error's own words, no internal class name")
        assertTrue(request.cause is ApiException.Http)
    }

    @Test fun everyNoFillReasonMapsFromTheWireAndBack() {
        val known = listOf(
            "noAdMatch" to NoFillReason.NoAdMatch,
            "contentUnsafe" to NoFillReason.ContentUnsafe,
            "formatNotEnabled" to NoFillReason.FormatNotEnabled,
            "frequencyCap" to NoFillReason.FrequencyCap,
            "geoRestricted" to NoFillReason.GeoRestricted,
            "temporaryError" to NoFillReason.TemporaryError,
            "alreadyUsed" to NoFillReason.AlreadyUsed,
            "unknown" to NoFillReason.Unknown,
        )
        for ((wire, reason) in known) {
            assertEquals(reason, NoFillReason.from(wire), wire)
            assertEquals(wire, reason.toString(), "and back: $wire")
        }
        assertEquals(NoFillReason.Unknown, NoFillReason.from(null), "absent is unknown")
        assertEquals(NoFillReason.Other("aReasonFromTheFuture"), NoFillReason.from("aReasonFromTheFuture"))
        assertEquals("aReasonFromTheFuture", NoFillReason.Other("aReasonFromTheFuture").toString())
    }
}

/** The Swift suite's request-body, device and consent rules, checked on this side. */
@RunWith(RobolectricTestRunner::class)
class ParityRequestsTest : SdkTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun request(content: Content = Content("v", "en"), labels: Map<String, String> = emptyMap(), held: List<String> = emptyList()) =
        runBlocking { AdsRequest.make(context, "pub-dev", content, labels, 12_345, "s-1", held) }

    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    /** A JSON boolean, not a string that reads as one. */
    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.takeIf { !it.isString }?.booleanOrNull

    private fun preferences() = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    private val publisher = Regulatory {
        gdpr = false
        gdprConsent = "PUB-TC"
        coppa = false
        gpp = "PUB-GPP"
        gppSid = listOf(9)
        usPrivacy = "1NNN"
    }

    @JUnit4Test fun theAdsRequestBodyHasEveryField() {
        Environment.advertisingId = { "6d92078a-8246-4ba4-ae5b-76104861e7dc" }
        KontextVideo.user = User { id = "u1" }
        KontextVideo.variantId = "exp-b"
        KontextVideo.regulatory = Regulatory { coppa = false }
        val built = request(Content("v1", "en") { durationMillis = 92_000 }, mapOf("stage" to "first_episode", "layout" to "fullscreen"), listOf("a-1", "a-2"))
        val body = built.body
        JUnit4.assertEquals(built.requestId, body.text("requestId"))
        for (absent in listOf("adServerUrl", "adServerURL", "formats", "placementId")) JUnit4.assertNull(absent, body[absent])
        JUnit4.assertEquals("pub-dev", body.text("publisherToken"))
        JUnit4.assertEquals(DeviceFacts.installId(context), body.text("installId"))
        JUnit4.assertEquals("u1", body.obj("user").text("id"))
        JUnit4.assertEquals("exp-b", body.text("variantId"))
        JUnit4.assertEquals(false, body.obj("ifa").bool("limitAdTracking"))
        JUnit4.assertEquals(false, body.obj("regulatory").bool("coppa"))
        JUnit4.assertEquals("s-1", body.text("sessionId"))
        JUnit4.assertEquals(listOf("a-1", "a-2"), body["held"]!!.jsonArray.map { it.jsonPrimitive.content })
        JUnit4.assertEquals(mapOf("stage" to "first_episode", "layout" to "fullscreen"), body.obj("labels").mapValues { it.value.jsonPrimitive.content })
        JUnit4.assertEquals("92000", body.obj("video").text("duration"))
        val player = body.obj("player")
        JUnit4.assertEquals(true, player.bool("fullscreen"))
        JUnit4.assertEquals("12345", player.text("playhead"))
        val device = body.obj("device")
        JUnit4.assertNotNull(device.obj("screen")["dpr"]!!.jsonPrimitive.doubleOrNull)
        JUnit4.assertEquals("test-agent", device.obj("network").text("userAgent"))
        JUnit4.assertNotNull("the server derives click-to-play from it", device.obj("power").bool("lowPowerMode"))
        JUnit4.assertNotNull(device["audio"])
    }

    @JUnit4Test fun anAdsRequestCarriesExactlyWhatWasSetAndNothingElse() {
        val bare = request()
        for (absent in listOf("user", "variantId", "labels", "regulatory", "ifa")) JUnit4.assertNull(absent, bare.body[absent])
        JUnit4.assertEquals("nothing held: an empty list, never absent", JsonArray(emptyList()), bare.body["held"])
        val again = request()
        JUnit4.assertNotEquals("a new id per request", bare.body.text("requestId"), again.body.text("requestId"))
        KontextVideo.user = User { id = "later" }
        JUnit4.assertEquals("a change reaches the next request", "later", request().body.obj("user").text("id"))
    }

    @Config(qualifiers = "w640dp-h360dp-land")
    @JUnit4Test
    fun thePlayerIsTheScreenInItsOrientationFullScreen() {
        val player = AdsRequest.player(context, 47_001)
        JUnit4.assertEquals("640", player.text("width"))
        JUnit4.assertEquals("360", player.text("height"))
        JUnit4.assertEquals(true, player.bool("fullscreen"))
        JUnit4.assertEquals("47001", player.text("playhead"))
    }

    @JUnit4Test fun deviceFactsHaveTheWireShape() {
        val screen = DeviceFacts.screen(context)
        JUnit4.assertTrue(screen.text("width").toInt() > 0)
        JUnit4.assertTrue(screen["dpr"]!!.jsonPrimitive.doubleOrNull!! >= 1)
        JUnit4.assertNotNull(screen.bool("darkMode"))
        val os = DeviceFacts.os(context)
        JUnit4.assertEquals("android", os.text("name"))
        JUnit4.assertTrue(os.text("locale").isNotEmpty() && os.text("timezone").isNotEmpty())
        JUnit4.assertTrue(DeviceFacts.hardware(context).text("type") in setOf("phone", "tablet", "tv", "settopbox", "desktop", "other"))
    }

    @JUnit4Test fun theConfigRequestCarriesTheIdentityAndEachHasItsOwnId() {
        val bodies = ConcurrentLinkedQueue<JsonObject>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/v2/config") bodies += Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                return MockResponse().setBody("""{"enabled": true}""")
            }
        }
        server.start()
        try {
            repeat(2) {
                Sdk.resetForTesting()
                var done = false
                KontextVideo.initialize(context, Configuration("pub-dev") { adServerUrl = server.url("/").toString() }) { done = true }
                val deadline = System.currentTimeMillis() + 10_000
                while (!done) {
                    shadowOf(Looper.getMainLooper()).idle()
                    if (System.currentTimeMillis() > deadline) JUnit4.fail("timed out")
                    Thread.sleep(5)
                }
            }
        } finally {
            server.shutdown()
        }
        val (first, second) = bodies.toList()
        JUnit4.assertTrue(first.text("requestId"), Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(first.text("requestId")))
        JUnit4.assertNotEquals(first.text("requestId"), second.text("requestId"))
        JUnit4.assertEquals("pub-dev", first.text("publisherToken"))
        JUnit4.assertEquals(DeviceFacts.installId(context), first.text("installId"))
        JUnit4.assertEquals("sdk-kotlin-video", first.obj("sdk").text("name"))
        JUnit4.assertEquals(context.packageName, first.obj("app").text("bundleId"))
        JUnit4.assertEquals("android", first.obj("device").obj("os").text("name"))
        JUnit4.assertTrue(first.obj("device").obj("hardware").text("brand").isNotEmpty())
    }

    @JUnit4Test fun consentReadsTheIabKeysAndCoppaOnlyFromThePublisher() {
        preferences().edit().putInt("IABTCF_gdprApplies", 1).putString("IABTCF_TCString", "TC-STRING").putString("IABGPP_HDR_GppString", "GPP-STRING")
            .putString("IABGPP_GppSID", "2_7").putString("IABUSPrivacy_String", "1YNN").commit()
        val regulatory = Consent.read(context, null)!!
        JUnit4.assertEquals(true, regulatory.gdpr)
        JUnit4.assertEquals("TC-STRING", regulatory.gdprConsent)
        JUnit4.assertEquals("GPP-STRING", regulatory.gpp)
        JUnit4.assertEquals(listOf(2, 7), regulatory.gppSid)
        JUnit4.assertEquals("1YNN", regulatory.usPrivacy)
        JUnit4.assertNull("coppa can only come from the publisher", regulatory.coppa)
    }

    @JUnit4Test fun consentPrecedenceForEveryField() {
        JUnit4.assertEquals("absent CMP values: the publisher's, false kept as false", publisher, Consent.read(context, publisher))
        preferences().edit().putInt("IABTCF_gdprApplies", 1).putString("IABTCF_TCString", "CMP-TC").putString("IABGPP_HDR_GppString", "CMP-GPP")
            .putString("IABGPP_GppSID", "2_7").putString("IABUSPrivacy_String", "1YNN").commit()
        val cmp = Regulatory {
            gdpr = true
            gdprConsent = "CMP-TC"
            coppa = false
            gpp = "CMP-GPP"
            gppSid = listOf(2, 7)
            usPrivacy = "1YNN"
        }
        JUnit4.assertEquals("each CMP value wins; coppa stays the publisher's", cmp, Consent.read(context, publisher))
        JUnit4.assertEquals(
            Regulatory {
                gdpr = true
                gdprConsent = "CMP-TC"
                gpp = "CMP-GPP"
                gppSid = listOf(2, 7)
                usPrivacy = "1YNN"
            },
            Consent.read(context, null),
        )
        preferences().edit().putString("IABTCF_gdprApplies", "yes").putString("IABTCF_TCString", "").putString("IABGPP_HDR_GppString", "")
            .putString("IABGPP_GppSID", "x_y").putString("IABUSPrivacy_String", "").commit()
        JUnit4.assertEquals("unusable CMP values: the publisher's", publisher, Consent.read(context, publisher))
        preferences().edit().putInt("IABTCF_gdprApplies", 0).putString("IABGPP_GppSID", "3").commit()
        val changed = Consent.read(context, publisher)!!
        JUnit4.assertEquals(false, changed.gdpr)
        JUnit4.assertEquals(listOf(3), changed.gppSid)
    }

    @JUnit4Test fun gdprAppliesIsReadAsCmpsWriteIt() {
        val key = "IABTCF_gdprApplies"
        val cases = listOf<Pair<Any, Boolean?>>(1 to true, 0 to false, true to true, "1" to true, "0" to false, 2 to null, "yes" to null, 1.5f to null)
        for ((raw, expected) in cases) {
            val editor = preferences().edit().clear()
            when (raw) {
                is Int -> editor.putInt(key, raw)
                is Boolean -> editor.putBoolean(key, raw)
                is Float -> editor.putFloat(key, raw)
                else -> editor.putString(key, raw.toString())
            }
            editor.commit()
            JUnit4.assertEquals("$raw", expected, Consent.read(context, null)?.gdpr)
        }
    }

    @JUnit4Test fun consentIsNullWhenNothingIsKnown() {
        preferences().edit().putString("IABTCF_TCString", "").putString("IABGPP_GppSID", "").commit()
        JUnit4.assertNull("empty strings count as absent", Consent.read(context, null))
    }

    @JUnit4Test fun gppSectionsAreReadSingleUnderscoredCommaSeparatedOrAsANumber() {
        for ((raw, expected) in listOf("7" to listOf(7), "8,10" to listOf(8, 10), "2_7" to listOf(2, 7), "x_y" to null)) {
            preferences().edit().putString("IABGPP_GppSID", raw).commit()
            JUnit4.assertEquals(raw, expected, Consent.read(context, null)?.gppSid)
        }
        preferences().edit().remove("IABGPP_GppSID").putInt("IABGPP_GppSID", 7).commit()
        JUnit4.assertEquals("a CMP writing one section as a number", listOf(7), Consent.read(context, null)?.gppSid)
    }

    @JUnit4Test fun everyFormatThrowsNotInitializedBeforeInitialize() {
        Sdk.resetForTesting()
        val ads = VideoAds(Content("v", "en")).also { owners += it }
        for (format in AdFormat.entries) {
            val error = runCatching { runBlocking { ads.format(format) } }.exceptionOrNull()
            JUnit4.assertTrue("$format: $error", error is VideoAdException.NotInitialized)
        }
    }
}
