package so.kontext.video

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.diagnostics.EventHistory

/** The debug events of an ad on screen and of the ads its `VideoAds` drops: their names, levels and properties, as Swift sends them. */
@RunWith(RobolectricTestRunner::class)
class DebugEventsTest : SdkTest() {
    private fun events(name: String): List<JsonObject> = EventHistory.recent().filter { it["name"]?.jsonPrimitive?.content == name }

    private fun JsonObject.property(key: String) = this["properties"]?.jsonObject?.get(key)?.jsonPrimitive?.content

    private fun JsonObject.level() = this["level"]?.jsonPrimitive?.content

    @Test fun aClickIsAnInfoEventWithItsArea() {
        val ad = overlay()
        ad.show(container())
        // One touch opens one URL: each click follows a touch of the viewer.
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", "\"url\": \"https://brand.test\", \"area\": \"endCard\"")
        ad.loadedBridge!!.noteTouch()
        page(ad, "ad.click", "\"url\": \"https://brand.test\", \"area\": \"cta\"")
        val clicks = events("ad.clicked")
        assertEquals(listOf("endCard", "cta"), clicks.map { it.property("area") })
        assertTrue("info: no level", clicks.all { it.level() == null })
        assertEquals(ad.id, clicks.first()["bidId"]?.jsonPrimitive?.content)
    }

    @Test fun aCreativeErrorIsAnErrorEventWithItsCode() {
        val ad = overlay()
        ad.show(container())
        page(ad, "ad.error", "\"code\": 405")
        val error = events("ad.error").single()
        assertEquals("405", error.property("code"))
        assertEquals("error", error.level())
    }

    @Test fun destroyingTheVideoAdsSaysEachHeldAdWasNotUsed() {
        val owner = owner()
        val ad = pause(owner)
        owner.ads[AdFormat.PAUSE] = ad
        owner.destroy()
        val notUsed = events("ad.notUsed").single()
        assertEquals("destroyed", notUsed.property("reason"))
        assertEquals(ad.id, notUsed["bidId"]?.jsonPrimitive?.content)
    }

    @Test fun aRejectSaysItsReason() {
        val ad = pause()
        ad.reject()
        assertEquals("notUsed", events("ad.notUsed").single().property("reason"))
    }
}
