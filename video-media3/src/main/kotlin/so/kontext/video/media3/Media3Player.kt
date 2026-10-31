@file:JvmName("Media3")

package so.kontext.video.media3

import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import so.kontext.video.VideoAds
import so.kontext.video.VideoAdsPlayer
import java.lang.ref.WeakReference

/**
 * Follows a Media3 [player] (an `ExoPlayer`, a `MediaController`): `ads.track(exoPlayer)`. The player must
 * run on the main looper, as the SDK reads it from the main thread. Held weakly.
 */
@MainThread
public fun VideoAds.track(player: Player) {
    track(Media3Player(player))
}

/**
 * Media3's `isPlaying` is false while buffering; the SDK's contract counts buffering as playing (the content
 * is about to move on), so playing here is: the player wants to play, is ready or buffering, and nothing
 * suppresses it.
 *
 * Media3 batches the events of one looper iteration and calls `onEvents` once, with the state as it is at the
 * end; a pause and a resume in the same iteration would be lost there. So the adapter follows the individual
 * callbacks, keeps its own copy of the three facts from their parameters, and tells the SDK each change.
 */
internal class Media3Player(player: Player) : VideoAdsPlayer {
    private val player = WeakReference(player)
    private val listeners = mutableListOf<VideoAdsPlayer.Listener>()
    private var playWhenReady = player.playWhenReady
    private var playbackState = player.playbackState
    private var suppression = player.playbackSuppressionReason
    private var lastPlaying = playing()

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            this@Media3Player.playWhenReady = playWhenReady
            playingMayHaveChanged()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            this@Media3Player.playbackState = playbackState
            playingMayHaveChanged()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            suppression = playbackSuppressionReason
            playingMayHaveChanged()
        }

        // Another episode, or the same one again from the start (a repeat): the overlay was over what ended.
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem == null) return
            listeners.toList().forEach { it.onContentChanged() }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                listeners.toList().forEach { it.onSeek() }
            }
        }
    }

    init {
        require(player.applicationLooper == Looper.getMainLooper()) { "the player must run on the main looper" }
    }

    override val currentPositionMillis: Long get() = player.get()?.currentPosition ?: 0

    override val isPlaying: Boolean get() = player.get()?.let { it.playWhenReady && it.playbackState.isActive() && it.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE } ?: false

    override val playbackSpeed: Float? get() = player.get()?.playbackParameters?.speed

    override fun addListener(listener: VideoAdsPlayer.Listener) {
        if (listeners.isEmpty()) {
            player.get()?.let {
                playWhenReady = it.playWhenReady
                playbackState = it.playbackState
                suppression = it.playbackSuppressionReason
                lastPlaying = playing()
                it.addListener(playerListener)
            }
        }
        listeners += listener
    }

    override fun removeListener(listener: VideoAdsPlayer.Listener) {
        listeners -= listener
        if (listeners.isEmpty()) player.get()?.removeListener(playerListener)
    }

    private fun playing(): Boolean = playWhenReady && playbackState.isActive() && suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    private fun playingMayHaveChanged() {
        val playing = playing()
        if (playing == lastPlaying) return
        lastPlaying = playing
        listeners.toList().forEach { it.onIsPlayingChanged(playing) }
    }

    private fun Int.isActive() = this == Player.STATE_READY || this == Player.STATE_BUFFERING
}
