package so.kontext.video.internal.payloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import so.kontext.video.internal.platform.Environment

/** The ads request's body: what the app and a consent platform set, the labels, the playhead, the held ads. */
@RunWith(RobolectricTestRunner::class)
class AdsRequestTest : SdkTest() {
    @Test fun theRequestCarriesTheUserConsentLabelsAndVariant() = runBlocking {
        KontextVideo.user = User {
            id = "u-1"
            keywords = listOf("drama")
        }
        // `false`, not `true`: a COPPA request would leave the user id out, and the id is what is checked.
        KontextVideo.regulatory = Regulatory {
            coppa = false
            gpp = "explicit-gpp"
        }
        KontextVideo.variantId = "b"
        // What a consent platform wrote: its values win over the app's, COPPA stays the app's.
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE).edit()
            .putString("IABGPP_HDR_GppString", "cmp-gpp").putString("IABGPP_GppSID", "2_7").putString("IABUSPrivacy_String", "1YNN").commit()
        val body = AdsRequest.make(context, "token", Content("ep", "en"), mapOf("stage" to "unlock"), 1_234, "session", { listOf("held-1") }).body
        assertEquals("u-1", body["user"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("b", body["variantId"]!!.jsonPrimitive.content)
        val regulatory = body["regulatory"]!!.jsonObject
        assertEquals("cmp-gpp", regulatory["gpp"]!!.jsonPrimitive.content)
        assertEquals(listOf(2, 7), regulatory["gppSid"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals("1YNN", regulatory["usPrivacy"]!!.jsonPrimitive.content)
        assertEquals("false", regulatory["coppa"]!!.jsonPrimitive.content)
        assertEquals("unlock", body["labels"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
        assertEquals("1234", body["player"]!!.jsonObject["playhead"]!!.jsonPrimitive.content)
        assertEquals("held-1", body["held"]!!.jsonArray.single().jsonPrimitive.content)
        assertTrue(body["device"]!!.jsonObject.keys.containsAll(listOf("os", "hardware", "screen", "power", "audio", "network")))
        assertNull("no ad id without the viewer's leave", body["ifa"])
    }

    @Test fun theHeldListIsReadOnceTheRequestIsBuilt() {
        var reads = 0
        val built = runBlocking {
            AdsRequest.make(Environment.context!!, "token", Content("ep", "en"), emptyMap(), 0, "session", {
                reads += 1
                listOf("h-1")
            })
        }
        assertEquals(1, reads)
        assertEquals(listOf("h-1"), built.held)
        assertEquals("[\"h-1\"]", built.body["held"].toString())
    }
}
