package so.kontext.video

import android.os.HandlerThread
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.content.ContentPlayback.Change
import so.kontext.video.internal.platform.Environment
import so.kontext.video.media3.track
import kotlin.concurrent.thread

/**
 * The Media3 adapter's rules, over a mocked Player whose callbacks the test makes: what each callback tells the
 * SDK, what the adapter reads, and what it never reads. (A real ExoPlayer plays a clip in the emulator tests.)
 */
@RunWith(RobolectricTestRunner::class)
class Media3AdapterTest : SdkTest() {
    private val episode = MediaItem.fromUri("https://v.test/ep1.mp4")
    private var playWhenReady = true
    private var state = Player.STATE_READY
    private var suppression = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private var playingAd = false
    private var item: MediaItem? = episode
    private val listener = slot<Player.Listener>()

    private fun player(looper: Looper = Looper.getMainLooper()) = mockk<Player>(relaxed = true).also { player ->
        every { player.applicationLooper } returns looper
        every { player.playWhenReady } answers { playWhenReady }
        every { player.playbackState } answers { state }
        every { player.playbackSuppressionReason } answers { suppression }
        every { player.isPlayingAd } answers { playingAd }
        every { player.contentPosition } returns 12_000
        every { player.currentMediaItem } answers { item }
        every { player.addListener(capture(listener)) } returns Unit
    }

    /** A tracked player and every change the SDK heard from it, as text. */
    private fun tracked(player: Player = player()): Pair<VideoAds, MutableList<String>> {
        val owner = owner()
        owner.track(player)
        val heard = mutableListOf<String>()
        val forward = owner.playback.onChange
        owner.playback.onChange = { change ->
            heard += when (change) {
                is Change.PlayingChanged -> "playing=${change.isPlaying}"
                Change.ContentChanged -> "content"
                Change.Seeked -> "seek"
                Change.Tick -> "tick"
            }
            forward?.invoke(change)
        }
        return owner to heard
    }

    private fun position(adGroup: Int) = Player.PositionInfo(null, 0, episode, null, 0, 0, 0, adGroup, if (adGroup == C.INDEX_UNSET) C.INDEX_UNSET else 0)

    @Test fun aPauseAndAResumeInOneIterationAreBothHeard() {
        val (owner, heard) = tracked()
        // Media3's onEvents would report the end state alone: playing, as before.
        playWhenReady = false
        listener.captured.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        playWhenReady = true
        listener.captured.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(listOf("playing=false", "playing=true"), heard)
        assertTrue(owner.playback.isPlaying)
    }

    @Test fun anAdThePlayerInsertsIsNotContent() {
        val (owner, heard) = tracked()
        playingAd = true
        listener.captured.onPositionDiscontinuity(position(C.INDEX_UNSET), position(0), Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals(listOf("playing=false"), heard)
        assertFalse(owner.playback.isPlaying)
        assertEquals("the content's position, not the ad's", 12_000, owner.playback.playheadMillis)
        playingAd = false
        listener.captured.onPositionDiscontinuity(position(0), position(C.INDEX_UNSET), Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals(listOf("playing=false", "playing=true"), heard)
    }

    @Test fun aRepeatIsAnotherPlayOfTheSameItem() {
        val (owner, heard) = tracked()
        assertFalse(owner.playback.playsAnotherEpisode)
        listener.captured.onMediaItemTransition(episode, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertEquals(listOf("content"), heard)
        assertTrue("the next play is not this episode's", owner.playback.playsAnotherEpisode)
    }

    @Test fun anEmptiedPlaylistIsNoChangeOfContent() {
        val (_, heard) = tracked()
        listener.captured.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertTrue(heard.isEmpty())
    }

    @Test fun aSeekAndItsAdjustmentAreSeeks() {
        val (_, heard) = tracked()
        listener.captured.onPositionDiscontinuity(position(C.INDEX_UNSET), position(C.INDEX_UNSET), Player.DISCONTINUITY_REASON_SEEK)
        listener.captured.onPositionDiscontinuity(position(C.INDEX_UNSET), position(C.INDEX_UNSET), Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT)
        listener.captured.onPositionDiscontinuity(position(C.INDEX_UNSET), position(C.INDEX_UNSET), Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertEquals(listOf("seek", "seek"), heard)
    }

    @Test fun suppressedPlaybackIsNotPlaying() {
        val (owner, heard) = tracked()
        suppression = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
        listener.captured.onPlaybackSuppressionReasonChanged(suppression)
        assertEquals(listOf("playing=false"), heard)
        assertFalse(owner.playback.isPlaying)
    }

    @Test fun bufferingCountsAsPlayingAndAReleasedPlayerDoesNot() {
        val (owner, heard) = tracked()
        state = Player.STATE_BUFFERING
        listener.captured.onPlaybackStateChanged(state)
        assertTrue("buffering: about to move on", heard.isEmpty() && owner.playback.isPlaying)
        // release() tells no listener; the player reads idle, so the next read says not playing.
        state = Player.STATE_IDLE
        assertFalse(owner.playback.isPlaying)
    }

    @Test fun aPlayerOnAnotherLooperIsNeverRead() {
        val thread = HandlerThread("player").apply { start() }
        try {
            val player = player(thread.looper)
            val (owner, _) = tracked(player)
            assertFalse(owner.playback.isPlaying)
            assertEquals(0, owner.playback.playheadMillis)
            verify(exactly = 0) { player.addListener(any()) }
            assertNotEquals(true, owner.playback.playsAnotherEpisode)
        } finally {
            thread.quitSafely()
        }
    }

    @Test fun trackFromAWorkerNeverReadsThePlayer() {
        val assertions = mutableListOf<String>()
        Environment.assertionFailure = { synchronized(assertions) { assertions += it } }
        val owner = owner()
        // ExoPlayer checks the calling thread on its getters: off its looper they throw.
        val strict = mockk<Player>(relaxed = true).also { player ->
            every { player.applicationLooper } returns Looper.getMainLooper()
            fun <T> onMain(value: T): T = value.also { check(Looper.myLooper() == Looper.getMainLooper()) { "Player is accessed on the wrong thread" } }
            every { player.playWhenReady } answers { onMain(true) }
            every { player.playbackState } answers { onMain(Player.STATE_READY) }
            every { player.playbackSuppressionReason } answers { onMain(Player.PLAYBACK_SUPPRESSION_REASON_NONE) }
            every { player.isPlayingAd } answers { onMain(false) }
            every { player.currentMediaItem } answers { onMain(episode) }
        }
        var thrown: Throwable? = null
        thread { thrown = runCatching { owner.track(strict) }.exceptionOrNull() }.join()
        assertEquals(null, thrown)
        assertTrue("$assertions", assertions.single().startsWith("KontextVideo: VideoAds.track() must be called on the main thread"))
        verify(exactly = 0) { strict.addListener(any()) }
    }

    @Test fun theSpeedIsThePlayersAndAnInfiniteOneIsUnknown() {
        var speed = 1.5f
        val player = player().also { every { it.playbackParameters } answers { androidx.media3.common.PlaybackParameters(speed) } }
        val (owner, _) = tracked(player)
        assertEquals(1.5f, owner.playback.playbackSpeed)
        speed = 0.5f
        assertEquals(0.5f, owner.playback.playbackSpeed)
        speed = Float.POSITIVE_INFINITY
        assertEquals("not a speed: unknown", null, owner.playback.playbackSpeed)
    }

    @Test fun aDestroyedVideoAdsTracksNothingAndReadsNothing() {
        val owner = owner()
        owner.destroy()
        val player = player()
        owner.track(player)
        assertFalse(owner.playback.isTracking)
        verify(exactly = 0) { player.addListener(any()) }
        verify(exactly = 0) { player.currentMediaItem }
    }

    @Test fun aNewPlaylistWithAnItemIsAChangeOfContent() {
        val (owner, heard) = tracked()
        val next = MediaItem.fromUri("https://v.test/ep2.mp4")
        item = next
        listener.captured.onMediaItemTransition(next, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertEquals(listOf("content"), heard)
        assertTrue(owner.playback.playsAnotherEpisode)
    }

    @Test fun anEndedOrPausedPlayerIsNotPlaying() {
        val (owner, heard) = tracked()
        state = Player.STATE_ENDED
        listener.captured.onPlaybackStateChanged(state)
        assertEquals(listOf("playing=false"), heard)
        state = Player.STATE_READY
        listener.captured.onPlaybackStateChanged(state)
        playWhenReady = false
        listener.captured.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        assertEquals(listOf("playing=false", "playing=true", "playing=false"), heard)
        assertFalse(owner.playback.isPlaying)
    }
}
