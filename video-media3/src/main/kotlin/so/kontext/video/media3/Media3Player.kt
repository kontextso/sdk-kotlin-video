@file:JvmName("Media3")

package so.kontext.video.media3

import android.os.Looper
import androidx.annotation.MainThread
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
 */
internal class Media3Player(player: Player) : VideoAdsPlayer {
    private val player = WeakReference(player)
    private val listeners = mutableListOf<VideoAdsPlayer.IsPlayingListener>()
    private var lastPlaying = isPlaying(player)
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val playing = isPlaying(player)
            if (playing == lastPlaying) return
            lastPlaying = playing
            listeners.toList().forEach { it.onIsPlayingChanged(playing) }
        }
    }

    init {
        require(player.applicationLooper == Looper.getMainLooper()) { "the player must run on the main looper" }
    }

    override val currentPositionMillis: Long get() = player.get()?.currentPosition ?: 0

    override val isPlaying: Boolean get() = player.get()?.let(::isPlaying) ?: false

    override fun addIsPlayingListener(listener: VideoAdsPlayer.IsPlayingListener) {
        if (listeners.isEmpty()) player.get()?.addListener(playerListener)
        listeners += listener
    }

    override fun removeIsPlayingListener(listener: VideoAdsPlayer.IsPlayingListener) {
        listeners -= listener
        if (listeners.isEmpty()) player.get()?.removeListener(playerListener)
    }

    private fun isPlaying(player: Player): Boolean = player.playWhenReady &&
        (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) &&
        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
}
