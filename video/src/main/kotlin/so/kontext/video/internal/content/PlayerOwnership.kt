package so.kontext.video.internal.content

import so.kontext.video.ContentPlayer
import java.lang.ref.WeakReference

/**
 * Which [ContentPlayback] tracks which player, across every `VideoAds`: a player belongs to one at a time. The
 * playback that claims a player takes it, and the one that tracked it before lets go
 * ([ContentPlayback.losePlayer]): its overlay and pause ads can no longer be shown.
 */
internal object PlayerOwnership {
    private val owners = mutableListOf<Owner>()

    /** One playback and the player it tracks, both weak. */
    private class Owner(playback: ContentPlayback, player: ContentPlayer) {
        val playback = WeakReference(playback)
        val player = WeakReference(player)
    }

    /**
     * [player] is [playback]'s now: another one that tracks it lets go, as if it had never tracked one, except
     * that its overlay and pause ads can no longer be shown (`ContentPlayback.playsAnotherEpisode`).
     */
    fun claim(playback: ContentPlayback, player: ContentPlayer) {
        owners.removeAll { it.playback.get().let { owner -> owner == null || owner === playback } || it.player.get() == null }
        val others = owners.filter { it.player.get() === player }.mapNotNull { it.playback.get() }
        owners.removeAll { it.player.get() === player }
        owners += Owner(playback, player)
        others.forEach { it.losePlayer() }
    }

    /** [playback] tracks nothing now (it stopped): it owns no player. */
    fun release(playback: ContentPlayback) {
        owners.removeAll { it.playback.get().let { owner -> owner == null || owner === playback } }
    }

    /** No player owned. Tests only: the ownership is process-wide. */
    fun resetForTesting() {
        owners.clear()
    }
}
