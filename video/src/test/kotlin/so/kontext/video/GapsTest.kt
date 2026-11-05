package so.kontext.video

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.KontextVideo.Content
import so.kontext.video.KontextVideo.Regulatory
import so.kontext.video.KontextVideo.User
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.content.ContentPlayback
import so.kontext.video.internal.network.ApiException
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.payloads.AdsRequest
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.screen.ScreenSlot

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

    private class Player(var playing: Boolean = true, var position: Long = 0) : ContentPlayer {
        override val currentPositionMillis get() = position
        override val isPlaying get() = playing
        val listeners = mutableListOf<ContentPlayer.Listener>()
        override fun addListener(listener: ContentPlayer.Listener) {
            listeners += listener
        }

        override fun removeListener(listener: ContentPlayer.Listener) {
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
        assertEquals(1_000, ad.contentClock!!.elapsedMillis)
        player.position = 900_000
        player.listeners.single().onSeek()
        now = 2_000
        player.position = 900_500
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertEquals("counting goes on from the seek", 1_500, ad.contentClock!!.elapsedMillis)
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

    // region Format rules the refactor into ContentRule and AdScreen must keep

    @Test fun aPauseAdsClockNeverGetsADuration() {
        // A pause payload carries no duration; one with a duration anyway must still stay until something ends it.
        val parsed = AdPayload.parse(Fixtures.json(Fixtures.pause()))
        val payload = AdPayload(
            parsed.id, parsed.format, parsed.creative, parsed.price, parsed.expiresMillis, parsed.frameUrl, parsed.tracking,
            parsed.position, parsed.sizes, 1_000, parsed.skipOffsetMillis,
        )
        val recorder = Recorder()
        val ad = loaded(payload)
        ad.listener = recorder
        ad.show(container())
        idle(5_000)
        assertTrue("no end from the clock", recorder.reasons.isEmpty())
        assertFalse(ad.hasEnded || ad.isCompleted)
        ad.dismiss()
        assertEquals(listOf(VideoAd.EndReason.DISMISSED), recorder.reasons)
    }

    @Test fun anOverlayShownBeforeTrackKeepsItsWallClockDurationAndEndsOnAPause() {
        // Shown with no player: its duration runs on the clock. A player tracked later does not move it to video
        // time (no ContentTimeClock), but a pause of that player ends it.
        val owner = owner()
        val recorder = Recorder()
        val ad = overlay(owner, duration = "2000")
        ad.listener = recorder
        ad.show(container())
        idle(500)
        val player = Player()
        owner.track(player)
        assertTrue("the ticks start for the overlay on screen", owner.playback.isTicking)
        owner.playback.onChange?.invoke(ContentPlayback.Change.Tick)
        assertNull("no video time for an overlay shown before track", ad.contentClock)
        assertFalse(ad.hasEnded)
        idle(1_600)
        assertEquals("its wall-clock duration elapsed", listOf(VideoAd.EndReason.COMPLETED), recorder.reasons)
        assertTrue(ad.isCompleted)

        val other = owner()
        val second = overlay(other, duration = "2000")
        val secondRecorder = Recorder()
        second.listener = secondRecorder
        second.show(container())
        val secondPlayer = Player()
        other.track(secondPlayer)
        assertNull(second.contentClock)
        secondPlayer.listeners.single().onIsPlayingChanged(false)
        assertEquals("a pause of the tracked player ends it", listOf(VideoAd.EndReason.INTERRUPTED), secondRecorder.reasons)
    }

    @Test fun aContainerThatRefusesAFullScreenPauseAdsHostLeavesItReadyAndTheSlotFree() {
        val owner = owner()
        val recorder = Recorder()
        val ad = pause(owner, position = "fullscreen")
        owner.ads[AdFormat.PAUSE] = ad
        ad.listener = recorder
        val activity = activity()
        val refusing = object : FrameLayout(activity) {
            override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams) {
                error("not here")
            }
        }
        activity.setContentView(refusing)
        refusing.layout(0, 0, 320, 400)
        idle()
        refusing.layout(0, 0, 320, 400)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(refusing, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        try {
            ad.show(refusing)
            org.junit.Assert.fail("shown")
        } catch (error: VideoAdException) {
            assertTrue("$error", error is VideoAdException.CannotShowNow)
        }
        assertNull("the slot is free", ScreenSlot.current)
        assertTrue("the ad stays ready", ad.isReady && !ad.wasShown)
        assertEquals("the owner holds it again", ad, owner.ads[AdFormat.PAUSE])
        assertEquals(0, refusing.childCount)
        assertTrue("nothing heard", recorder.heard.isEmpty())
        ad.show(container())
        assertTrue(ad.wasShown)
    }

    // endregion
}
