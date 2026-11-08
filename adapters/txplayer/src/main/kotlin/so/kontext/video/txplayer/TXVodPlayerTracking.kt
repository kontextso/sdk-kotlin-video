@file:JvmName("TXVodPlayers")

package so.kontext.video.txplayer

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.tencent.rtmp.TXVodPlayer
import so.kontext.video.VideoAds
import java.util.WeakHashMap

/**
 * Follows a Tencent Cloud Player SDK [player] (`TXVodPlayer`, 11.0 or later): `ads.track(player)`. Held weakly.
 *
 * A `TXVodPlayer` has one listener slot, the app's own `setVodListener`: the SDK does not take it. Hand the play
 * events on from the app's `ITXVodPlayListener.onPlayEvent` with [KontextTXVodPlayer.onPlayEvent], so the SDK hears
 * the end of the video, a loop's next round and a seek at once. Without them it still follows the player, reading
 * its position and `isPlaying()` (and estimating seeks from the position).
 *
 * In a feed, where each page has its own player, track the page's player with that page's `VideoAds` once it has
 * its source. [currentItem] is the app's episode on this player (its id), when one player plays several episodes;
 * without it the player is the episode. Either way, playing it to its end, or a loop's next round, is another play.
 * The Player SDK has no getter for the playback speed: pass [playbackSpeed] when the app plays faster or slower
 * than 1× (`setRate`); without it the SDK accepts up to about 4×. The player keeps both closures while it is
 * tracked: one that captures what holds this `VideoAds` (an Activity's property) keeps that alive as long as the
 * player, until `destroy()`.
 *
 * Show an episode's ads only in that episode: the same player tracked by another `VideoAds` is let go of here, and
 * this one's overlay and pause ads then throw `Invalidated(ContentChanged)` at `show`; so do they once [currentItem]
 * gives another value, or the video was played to its end.
 */
@MainThread
@JvmOverloads
public fun VideoAds.track(player: TXVodPlayer, currentItem: (() -> Any?)? = null, playbackSpeed: (() -> Float)? = null) {
    // Off the main thread or destroyed, the shared adapter is not touched: `track` refuses (and says why) with
    // a throwaway one, and the closures of the VideoAds that does track the player stay.
    if (Looper.myLooper() != Looper.getMainLooper() || isDestroyed) {
        track(VodPlayer(TXVod(player)))
        return
    }
    // One adapter per player: the SDK tells a player tracked by two `VideoAds` by the adapter it is given, and the
    // app's events find it here.
    adapters.getOrPut(player) { VodPlayer(TXVod(player)) }.bind(currentItem, playbackSpeed) { track(it) }
}

/** The app's side of the Player SDK's one listener slot. */
public object KontextTXVodPlayer {
    /**
     * Hands one play event of [player] on to the SDK: call it from the app's `ITXVodPlayListener.onPlayEvent`, with
     * the event as given, every event. Nothing happens for a player no `VideoAds` tracks. Safe from any thread.
     */
    @JvmStatic
    public fun onPlayEvent(player: TXVodPlayer, event: Int) {
        // The Player SDK calls back on the main thread; an app that hands an event on from elsewhere is posted there.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { onPlayEvent(player, event) }
            return
        }
        adapters[player]?.playEvent(event)
    }
}

/** The adapter of each player tracked, for as long as the player lives. The adapter holds it weakly. */
private val adapters = WeakHashMap<TXVodPlayer, VodPlayer>()
