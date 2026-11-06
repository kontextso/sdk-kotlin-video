package so.kontext.video.internal.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.ContentPlayer
import so.kontext.video.SdkTest
import so.kontext.video.VideoAd
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment

/** What the content does to an overlay or pause ad on screen: a seek, a pause, another item, a player tracked while it shows. */
@RunWith(RobolectricTestRunner::class)
class ContentRuleTest : SdkTest() {
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

    private fun playing(playing: Boolean = true) = object : ContentPlayer {
        override val currentPositionMillis = 0L
        override val isPlaying = playing
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

    @Test fun theFirstTrackWhileAnOverlayIsUpLeavesIt() {
        val owner = owner()
        val ad = overlay(owner)
        val recorder = Recorder().also { ad.listener = it }
        ad.show(container())
        owner.track(playing())
        idle()
        assertFalse("it followed no player before: nothing changed for it", ad.hasEnded)
        owner.track(playing())
        idle()
        assertEquals("another player than the one it followed: the content changed", listOf(VideoAd.EndReason.INTERRUPTED), recorder.reasons)
    }
}
