package so.kontext.video.internal.payloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.SdkTest
import so.kontext.video.VideoAdException
import so.kontext.video.internal.device.DeviceInfo
import so.kontext.video.internal.platform.Environment

/** The ads request's body: what the app and a consent platform set, the section and variant, the playhead, the network. */
@RunWith(RobolectricTestRunner::class)
class AdsRequestTest : SdkTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun make(section: String? = null, variantId: String? = null) =
        runBlocking { AdsRequest.make(context, "token", Content("ep", "en"), section, variantId, 1_234, "session") }

    @Test fun theRequestCarriesTheUserConsentSectionAndVariant() {
        KontextVideo.user = User("u-1") {
            keywords = listOf("drama")
        }
        KontextVideo.regulatory = Regulatory {
            coppa = false
            gpp = "explicit-gpp"
        }
        // What a consent platform wrote: its values win over the app's, COPPA stays the app's.
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE).edit()
            .putString("IABGPP_HDR_GppString", "cmp-gpp").putString("IABGPP_GppSID", "2_7").putString("IABUSPrivacy_String", "1YNN").commit()
        val body = make(section = "feed", variantId = "b").body
        assertEquals("u-1", body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("b", body["variantId"]!!.jsonPrimitive.content)
        assertEquals("feed", body["section"]!!.jsonPrimitive.content)
        val regulatory = body["regulatory"]!!.jsonObject
        assertEquals("cmp-gpp", regulatory["gpp"]!!.jsonPrimitive.content)
        assertEquals(listOf(2, 7), regulatory["gppSid"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals("1YNN", regulatory["usPrivacy"]!!.jsonPrimitive.content)
        assertEquals("false", regulatory["coppa"]!!.jsonPrimitive.content)
        assertEquals("1234", body["player"]!!.jsonObject["playhead"]!!.jsonPrimitive.content)
        assertTrue(body["device"]!!.jsonObject.keys.containsAll(listOf("os", "hardware", "screen", "power", "audio", "network")))
        assertNull("no ad id without the viewer's leave", body["ifa"])
    }

    @Test fun whatTheContractDroppedIsNeverSent() {
        val body = make(section = "feed").body
        for (gone in listOf("held", "labels")) assertNull(gone, body[gone])
        assertNull("no categories", body["video"]!!.jsonObject["categories"])
        assertNull("no variant unless the VideoAds has one", body["variantId"])
        assertNull(make().body["section"])
    }

    @Test fun aChildsRequestKeepsTheUserIdAndLeavesOutTheEmailAndItsHash() {
        Environment.advertisingId = { "6d92078a-8246-4ba4-ae5b-76104861e7dc" }
        KontextVideo.user = User("kid-1") {
            email = "kid@example.com"
            emailHash = "a".repeat(64)
            keywords = listOf("cartoons")
        }
        KontextVideo.regulatory = Regulatory { coppa = true }
        val body = make().body
        assertEquals(Json.parseToJsonElement("""{"id": "kid-1", "keywords": ["cartoons"]}"""), body["user"])
        assertNull("no advertising id for a child", body["ifa"])
        KontextVideo.user = User("kid-2")
        assertEquals(Json.parseToJsonElement("""{"id": "kid-2"}"""), make().body["user"])
    }

    @Test fun withoutAUsableUserNoRequestIsMade() {
        for (user in listOf(null, User(""))) {
            KontextVideo.user = user
            val error = runCatching { make() }.exceptionOrNull()
            assertEquals("$user", VideoAdException.RequestFailure.MissingUser, (error as VideoAdException.Request).failure)
        }
    }

    @Test fun theNetworkFactsGoWithTheUserAgentWhenTheDeviceSaysThem() {
        DeviceInfo.readNetworkFacts = { DeviceInfo.NetworkFacts("cellular", "5g", "Carrier", "230-02") }
        assertEquals(
            Json.parseToJsonElement("""{"userAgent": "test-agent", "type": "cellular", "generation": "5g", "carrier": "Carrier", "mccmnc": "230-02"}"""),
            make().body["device"]!!.jsonObject["network"],
        )
        DeviceInfo.readNetworkFacts = { DeviceInfo.NetworkFacts("wifi", null, null, null) }
        assertEquals(Json.parseToJsonElement("""{"userAgent": "test-agent", "type": "wifi"}"""), make().body["device"]!!.jsonObject["network"])
        DeviceInfo.readNetworkFacts = { error("no service") }
        assertEquals("a read that throws costs only the facts", Json.parseToJsonElement("""{"userAgent": "test-agent"}"""), make().body["device"]!!.jsonObject["network"])
    }

    @Test fun kontextKitsRadioIsTheContractsGenerationAndTheOperatorItsMccMnc() {
        val generations = mapOf(
            "gprs" to "2g", "edge" to "2g", "2g" to "2g", "3g" to "3g", "hspa" to "3g", "lte" to "4g", "5g" to "5g", "nr" to "5g",
            "iwlan" to null, null to null,
        )
        for ((detail, generation) in generations) assertEquals("$detail", generation, DeviceInfo.generationOf(detail))
        assertEquals("230-02", DeviceInfo.mccmncOf("23002"))
        assertEquals("310-260", DeviceInfo.mccmncOf("310260"))
        for (bad in listOf(null, "", "2300", "2300211", "23O02")) assertNull("$bad", DeviceInfo.mccmncOf(bad))
    }

    @Test fun beforeKontextKitHasTheUserAgentTheNetworkIsNotReadAtAll() {
        // The default reader: KontextKit's read would start a WebView for the user agent, so it waits for the lookup.
        assertNull(DeviceInfo.readNetworkFacts(context))
    }
}
