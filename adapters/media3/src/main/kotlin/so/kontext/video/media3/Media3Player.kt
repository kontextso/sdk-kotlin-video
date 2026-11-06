@file:JvmName("Media3")

package so.kontext.video.media3

import android.os.Looper
import android.util.Log
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import so.kontext.video.ContentPlayer
import so.kontext.video.VideoAds
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Follows a Media3 [player] (an `ExoPlayer`, a `MediaController`): `ads.track(exoPlayer)`. The player must
 * run on the main looper, as the SDK reads it from the main thread; one on another looper is not followed
 * (an error in the log), and the content counts as not playing. Held weakly. While the player plays an ad of
 * its own (an ads loader, inserted ads), the content counts as not playing and its position is the content's.
 *
 * Show an episode's ads only in that episode: the same player tracked by another `VideoAds` is let go of
 * here, and this one's overlay and pause ads then throw `Invalidated(ContentChanged)` at `show`; so do they
 * once the player's current media item is another than at `track` (or, with none then, the first it got),
 * a repeat's next play included.
 */
@MainThread
public fun VideoAds.track(player: Player) {
    // Off the main thread or destroyed, neither the player (its getters throw off its looper) nor the shared
    // registry is touched: `track` refuses (and says why) with a stand-in.
    if (Looper.myLooper() != Looper.getMainLooper() || isDestroyed) {
        track(Refused)
        return
    }
    // One adapter per player: the SDK tells a player tracked by two `VideoAds` by the adapter it is given.
    track(adapters.getOrPut(player) { Media3Player(player) })
}

/** What an off-main or destroyed `track` is given: `track` refuses it without reading anything. */
private object Refused : ContentPlayer {
    override val currentPositionMillis: Long get() = 0
    override val isPlaying: Boolean get() = false
}

/** The adapter of each player tracked, for as long as the player lives. The adapter holds it weakly. */
private val adapters = WeakHashMap<Player, Media3Player>()

/**
 * Media3's `isPlaying` is false while buffering; the SDK's contract counts buffering as playing (the content
 * is about to move on), so playing here is: the player wants to play, is ready or buffering, and nothing
 * suppresses it.
 *
 * Media3 batches the events of one looper iteration and calls `onEvents` once, with the state as it is at the
 * end; a pause and a resume in the same iteration would be lost there. So the adapter follows the individual
 * callbacks, keeps its own copy of the facts from their parameters, and tells the SDK each change.
 *
 * An ad the player inserts itself is not content: an overlay over it would sit on another ad, and its
 * position is the ad's. Every move into or out of such an ad is a position discontinuity whose new position
 * names its ad group.
 */
internal class Media3Player(player: Player) : ContentPlayer {
    // A player on another looper is never read: the SDK reads on the main thread, which would be a crash in
    // the player's own checks, or a race. Not thrown either: a release build carries on, without the player.
    private val player = WeakReference(player.takeIf { it.applicationLooper == Looper.getMainLooper() })
    private val listeners = mutableListOf<ContentPlayer.Listener>()
    private var playWhenReady = this.player.get()?.playWhenReady ?: false
    private var playbackState = this.player.get()?.playbackState ?: Player.STATE_IDLE
    private var suppression = this.player.get()?.playbackSuppressionReason ?: Player.PLAYBACK_SUPPRESSION_REASON_NONE
    private var isPlayingAd = this.player.get()?.isPlayingAd ?: false
    private var wasPlaying = isPlayingNow()

    /** Media item transitions heard while followed: each is another play, of another item or the same one. */
    private var plays = 0

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
            plays += 1
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && mediaItem == null) return
            listeners.toList().forEach { it.onContentChanged() }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            isPlayingAd = newPosition.adGroupIndex != C.INDEX_UNSET
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                listeners.toList().forEach { it.onSeek() }
            }
            playingMayHaveChanged()
        }
    }

    init {
        if (this.player.get() == null) {
            Log.e("KontextVideo", "track(player): the player runs on another looper, not the main one; it is not followed and the content counts as not playing")
        }
    }

    /** The content's position, also while the player plays an ad of its own. */
    override val currentPositionMillis: Long get() = player.get()?.contentPosition ?: 0

    override val isPlaying: Boolean
        get() = player.get()?.let {
            it.playWhenReady && it.playbackState.isActive() && it.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE && !it.isPlayingAd
        } ?: false

    override val playbackSpeed: Float? get() = player.get()?.playbackParameters?.speed

    /** The media item now, and which of the transitions heard brought it: a repeat plays the same item anew. */
    override val currentItem: Any? get() = player.get()?.currentMediaItem?.let { Play(it, plays) }

    /** One play of a media item. */
    private class Play(val item: MediaItem, val count: Int) {
        override fun equals(other: Any?): Boolean = other is Play && item == other.item && count == other.count

        override fun hashCode(): Int = item.hashCode() * 31 + count
    }

    override fun addListener(listener: ContentPlayer.Listener) {
        if (listeners.isEmpty()) {
            player.get()?.let {
                playWhenReady = it.playWhenReady
                playbackState = it.playbackState
                suppression = it.playbackSuppressionReason
                isPlayingAd = it.isPlayingAd
                wasPlaying = isPlayingNow()
                it.addListener(playerListener)
            }
        }
        listeners += listener
    }

    override fun removeListener(listener: ContentPlayer.Listener) {
        listeners -= listener
        if (listeners.isEmpty()) player.get()?.removeListener(playerListener)
    }

    private fun isPlayingNow(): Boolean = playWhenReady && playbackState.isActive() && suppression == Player.PLAYBACK_SUPPRESSION_REASON_NONE && !isPlayingAd

    private fun playingMayHaveChanged() {
        val isPlayingNow = isPlayingNow()
        if (isPlayingNow == wasPlaying) return
        wasPlaying = isPlayingNow
        listeners.toList().forEach { it.onIsPlayingChanged(isPlayingNow) }
    }

    private fun Int.isActive() = this == Player.STATE_READY || this == Player.STATE_BUFFERING
}
