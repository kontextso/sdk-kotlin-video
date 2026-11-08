package so.kontext.video.txplayer

import com.tencent.rtmp.TXVodPlayer
import java.lang.ref.WeakReference

/** A `TXVodPlayer`, held weakly. */
internal class TXVod(player: TXVodPlayer) : Vod {
    private val player = WeakReference(player)

    override val isGone: Boolean get() = player.get() == null
    override val positionSeconds: Float get() = player.get()?.currentPlaybackTime ?: 0f
    override val isPlaying: Boolean get() = player.get()?.isPlaying ?: false
}
