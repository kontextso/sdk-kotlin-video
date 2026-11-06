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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import so.kontext.video.AdFormat
import so.kontext.video.KontextVideo
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.SdkTest
import so.kontext.video.VideoAdException
import so.kontext.video.VideoAds
import so.kontext.video.internal.device.Consent
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.payloads.AdsRequest
import so.kontext.video.internal.platform.Environment
import java.util.concurrent.ConcurrentLinkedQueue

/** The Swift suite's request-body, device and consent rules, checked on this side. */
@RunWith(RobolectricTestRunner::class)
class ParityRequestsTest : SdkTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val publisher = Regulatory {
        gdpr = false
        gdprConsent = "PUB-TC"
        coppa = false
        gpp = "PUB-GPP"
        gppSid = listOf(9)
        usPrivacy = "1NNN"
    }

    private fun request(content: Content = Content("v", "en"), labels: Map<String, String> = emptyMap(), held: List<String> = emptyList()) =
        runBlocking { AdsRequest.make(context, "pub-dev", content, labels, 12_345, "s-1", { held }) }

    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    /** A JSON boolean, not a string that reads as one. */
    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.takeIf { !it.isString }?.booleanOrNull

    private fun preferences() = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    @Test fun theAdsRequestBodyHasEveryField() {
        Environment.advertisingId = { "6d92078a-8246-4ba4-ae5b-76104861e7dc" }
        KontextVideo.user = User { id = "u1" }
        KontextVideo.variantId = "exp-b"
        KontextVideo.regulatory = Regulatory { coppa = false }
        val built = request(Content("v1", "en") { durationMillis = 92_000 }, mapOf("stage" to "first_episode", "layout" to "fullscreen"), listOf("a-1", "a-2"))
        val body = built.body
        assertEquals(built.requestId, body.text("requestId"))
        for (absent in listOf("adServerUrl", "adServerURL", "formats", "placementId")) assertNull(absent, body[absent])
        assertEquals("pub-dev", body.text("publisherToken"))
        assertEquals(DeviceInfo.installId(context), body.text("installId"))
        assertEquals("u1", body.obj("user").text("id"))
        assertEquals("exp-b", body.text("variantId"))
        assertEquals(false, body.obj("ifa").bool("limitAdTracking"))
        assertEquals(false, body.obj("regulatory").bool("coppa"))
        assertEquals("s-1", body.text("sessionId"))
        assertEquals(listOf("a-1", "a-2"), body["held"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(mapOf("stage" to "first_episode", "layout" to "fullscreen"), body.obj("labels").mapValues { it.value.jsonPrimitive.content })
        assertEquals("92000", body.obj("video").text("duration"))
        val player = body.obj("player")
        assertEquals(true, player.bool("fullscreen"))
        assertEquals("12345", player.text("playhead"))
        val device = body.obj("device")
        assertNotNull(device.obj("screen")["dpr"]!!.jsonPrimitive.doubleOrNull)
        assertEquals("test-agent", device.obj("network").text("userAgent"))
        assertNotNull("the server derives click-to-play from it", device.obj("power").bool("lowPowerMode"))
        assertNotNull(device["audio"])
    }

    @Test fun aCoppaRequestCarriesNoIdentifierForAdvertising() {
        Environment.advertisingId = { "6d92078a-8246-4ba4-ae5b-76104861e7dc" }
        KontextVideo.user = User {
            id = "u1"
            email = "kid@example.com"
            emailHash = "h"
            keywords = listOf("cartoons")
        }
        KontextVideo.regulatory = Regulatory { coppa = true }
        val body = request().body
        assertNull("no advertising id for a child", body["ifa"])
        assertEquals("no id, email or hash; keywords stay", Json.parseToJsonElement("""{"keywords": ["cartoons"]}"""), body["user"])
        assertEquals(true, body.obj("regulatory").bool("coppa"))

        KontextVideo.user = User { id = "u1" }
        assertNull("nothing left of the user", request().body["user"])
    }

    @Test fun anAdsRequestCarriesExactlyWhatWasSetAndNothingElse() {
        val bare = request()
        for (absent in listOf("user", "variantId", "labels", "regulatory", "ifa")) assertNull(absent, bare.body[absent])
        assertEquals("nothing held: an empty list, never absent", JsonArray(emptyList()), bare.body["held"])
        val again = request()
        assertNotEquals("a new id per request", bare.body.text("requestId"), again.body.text("requestId"))
        KontextVideo.user = User { id = "later" }
        assertEquals("a change reaches the next request", "later", request().body.obj("user").text("id"))
    }

    @Config(qualifiers = "w640dp-h360dp-land")
    @Test
    fun thePlayerIsTheScreenInItsOrientationFullScreen() {
        val player = AdsRequest.player(context, 47_001)
        assertEquals("640", player.text("width"))
        assertEquals("360", player.text("height"))
        assertEquals(true, player.bool("fullscreen"))
        assertEquals("47001", player.text("playhead"))
    }

    @Test fun deviceFactsHaveTheWireShape() {
        val screen = DeviceInfo.screen(context)
        assertTrue(screen.text("width").toInt() > 0)
        val metrics = context.resources.displayMetrics
        assertEquals("in dp, not physical pixels", Math.round(metrics.widthPixels / metrics.density), screen.text("width").toInt())
        assertTrue(screen["dpr"]!!.jsonPrimitive.doubleOrNull!! >= 1)
        assertNotNull(screen.bool("darkMode"))
        val os = DeviceInfo.os(context)
        assertEquals("android", os.text("name"))
        assertTrue(os.text("locale").isNotEmpty() && os.text("timezone").isNotEmpty())
        assertTrue(DeviceInfo.hardware(context).text("type") in setOf("phone", "tablet", "tv", "desktop", "other"))
    }

    @Test fun theConfigRequestCarriesTheIdentityAndEachHasItsOwnId() {
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
                SdkInitializer.resetForTesting()
                var done = false
                KontextVideo.initialize(context, Configuration("pub-dev") { adServerUrl = server.url("/").toString() }) { done = true }
                val deadline = System.currentTimeMillis() + 10_000
                while (!done) {
                    shadowOf(Looper.getMainLooper()).idle()
                    if (System.currentTimeMillis() > deadline) fail("timed out")
                    Thread.sleep(5)
                }
            }
        } finally {
            server.shutdown()
        }
        val (first, second) = bodies.toList()
        assertTrue(first.text("requestId"), Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(first.text("requestId")))
        assertNotEquals(first.text("requestId"), second.text("requestId"))
        assertEquals("pub-dev", first.text("publisherToken"))
        assertEquals(DeviceInfo.installId(context), first.text("installId"))
        assertEquals("sdk-kotlin-video", first.obj("sdk").text("name"))
        assertEquals(context.packageName, first.obj("app").text("bundleId"))
        assertEquals("android", first.obj("device").obj("os").text("name"))
        assertTrue(first.obj("device").obj("hardware").text("brand").isNotEmpty())
    }

    @Test fun consentReadsTheIabKeysAndCoppaOnlyFromThePublisher() {
        preferences().edit().putInt("IABTCF_gdprApplies", 1).putString("IABTCF_TCString", "TC-STRING").putString("IABGPP_HDR_GppString", "GPP-STRING")
            .putString("IABGPP_GppSID", "2_7").putString("IABUSPrivacy_String", "1YNN").commit()
        val regulatory = Consent.read(context, null)!!
        assertEquals(true, regulatory.gdpr)
        assertEquals("TC-STRING", regulatory.gdprConsent)
        assertEquals("GPP-STRING", regulatory.gpp)
        assertEquals(listOf(2, 7), regulatory.gppSid)
        assertEquals("1YNN", regulatory.usPrivacy)
        assertNull("coppa can only come from the publisher", regulatory.coppa)
    }

    @Test fun consentPrecedenceForEveryField() {
        assertEquals("absent CMP values: the publisher's, false kept as false", publisher, Consent.read(context, publisher))
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
        assertEquals("each CMP value wins; coppa stays the publisher's", cmp, Consent.read(context, publisher))
        assertEquals(
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
        assertEquals("unusable CMP values: the publisher's", publisher, Consent.read(context, publisher))
        preferences().edit().putInt("IABTCF_gdprApplies", 0).putString("IABGPP_GppSID", "3").commit()
        val changed = Consent.read(context, publisher)!!
        assertEquals(false, changed.gdpr)
        assertEquals(listOf(3), changed.gppSid)
    }

    @Test fun gdprAppliesIsReadAsCmpsWriteIt() {
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
            assertEquals("$raw", expected, Consent.read(context, null)?.gdpr)
        }
    }

    @Test fun consentIsNullWhenNothingIsKnown() {
        preferences().edit().putString("IABTCF_TCString", "").putString("IABGPP_GppSID", "").commit()
        assertNull("empty strings count as absent", Consent.read(context, null))
    }

    @Test fun gppSectionsAreReadSingleUnderscoredCommaSeparatedOrAsANumber() {
        // Parts that are not numbers are skipped, as KontextKit reads them on both platforms.
        for ((raw, expected) in listOf("7" to listOf(7), "8,10" to listOf(8, 10), "2_7" to listOf(2, 7), "2_x_7" to listOf(2, 7), "x_y" to null)) {
            preferences().edit().putString("IABGPP_GppSID", raw).commit()
            assertEquals(raw, expected, Consent.read(context, null)?.gppSid)
        }
        preferences().edit().remove("IABGPP_GppSID").putInt("IABGPP_GppSID", 7).commit()
        assertEquals("a CMP writing one section as a number", listOf(7), Consent.read(context, null)?.gppSid)
    }

    @Test fun everyFormatThrowsNotInitializedBeforeInitialize() {
        SdkInitializer.resetForTesting()
        val ads = VideoAds(Content("v", "en")).also { owners += it }
        for (format in AdFormat.entries) {
            val error = runCatching { runBlocking { ads.format(format) } }.exceptionOrNull()
            assertTrue("$format: $error", error is VideoAdException.NotInitialized)
        }
    }
}
