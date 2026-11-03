package so.kontext.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.device.DeviceFacts
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.payloads.AdsRequest
import so.kontext.video.internal.playback.ContentPlayback
import so.kontext.video.internal.support.Environment

/** What the other suites leave: the full request body, the payload's last refusals, the content rules' paths. */
@RunWith(RobolectricTestRunner::class)
class GapsTest : SdkTest() {
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

    @Test fun everyFormFactorHasItsContractName() {
        assertEquals(
            listOf("phone", "phone", "tablet", "tv", "settopbox", "desktop", "other"),
            listOf("handset", "phone", "tablet", "tv", "settopbox", "desktop", "car").map(DeviceFacts::contractType),
        )
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
        val window = AdPayload.parse(Fixtures.json(Fixtures.overlay().replace("{\"kind\": \"manual\"}", "{\"kind\": \"window\", \"from\": 1, \"to\": 2}")))
        assertEquals("a timed answer is still an ad", "ad-1", window.id)
    }

    private class Player(var playing: Boolean = true, var position: Long = 0) : VideoAdsPlayer {
        override val currentPositionMillis get() = position
        override val isPlaying get() = playing
        val listeners = mutableListOf<VideoAdsPlayer.Listener>()
        override fun addListener(listener: VideoAdsPlayer.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: VideoAdsPlayer.Listener) {
            listeners -= listener
        }
    }

    @Test fun aSeekResyncsTheOverlaysVideoTimeAndAnotherItemEndsIt() {
        var now = 0L
        Environment.now = { now }
        val player = Player()
        val owner = owner()
        owner.track(player)
        val recorder = Recorder()
        val ad = overlay(owner, duration = "2000")
        ad.listener = recorder
        ad.show(container())
        now = 1_000
        player.position = 1_000
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertEquals(1_000, ad.contentProgress!!.elapsedMillis)
        player.position = 900_000
        player.listeners.single().onSeek()
        now = 2_000
        player.position = 900_500
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertEquals("counting goes on from the seek", 1_500, ad.contentProgress!!.elapsedMillis)
        player.listeners.single().onContentChanged()
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }

    @Test fun aPauseFromTheListenerEndsTheOverlayAndAPlayerThatStoppedOnATickToo() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val first = overlay(owner)
        val recorder = Recorder()
        first.listener = recorder
        first.show(container())
        player.listeners.single().onIsPlayingChanged(false)
        assertEquals(listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
        player.playing = true
        val second = overlay(owner)
        second.show(container())
        player.playing = false // no listener call: the next tick notices
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertTrue(second.hasEnded)
    }

    @Test fun anOverlayOverStoppedContentEndsBeforeAnotherAdShows() {
        val player = Player()
        val owner = owner()
        owner.track(player)
        val overlay = overlay(owner)
        overlay.show(container())
        player.playing = false // paused, not yet noticed
        val pause = pause(owner)
        pause.show(container())
        assertTrue(overlay.hasEnded)
        assertTrue(pause.wasShown)
    }

    @Test fun aListenerNeedsNoMethods() {
        val listener = object : VideoAdListener {}
        val ad = overlay()
        listener.onAdImpression(ad)
        listener.onAdClicked(ad, VideoAd.Click(VideoAd.Click.Area.Creative))
        listener.onAdFailed(ad, VideoAdException.NotLoaded())
        listener.onAdEnded(ad, VideoAd.EndReason.DISMISSED)
        assertFalse(ad.hasEnded)
    }
}
