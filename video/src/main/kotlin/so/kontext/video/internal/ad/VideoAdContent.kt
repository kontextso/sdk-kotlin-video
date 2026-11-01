package so.kontext.video.internal.ad

import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.playback.ContentPlayback
import so.kontext.video.internal.playback.ContentProgress
import so.kontext.video.internal.support.Logger

// An overlay lives only while the content plays, when the `VideoAds` tracks a player: a pause, the end of
// the content, another player or a player that is gone ends it as INTERRUPTED; its duration counts
// milliseconds of video; and it cannot be shown over paused content. Without a tracked player it runs on
// its own time.
//
// A pause ad lives only while the content is paused, when the `VideoAds` tracks a player: the content playing
// again ends it as INTERRUPTED, wherever the viewer resumed (the media notification, the lock screen, a
// headset, the picture-in-picture window); RESUMED stays the viewer's tap on the ad's own Resume. Only a
// return to playing counts: a pause ad shown a moment before the app pauses the player stays.

/** This overlay follows the content: its `VideoAds` tracks a player. */
internal val VideoAd.followsContent: Boolean get() = format == AdFormat.OVERLAY && owner.get()?.playback?.isTracking == true

/** This pause ad follows the content: its `VideoAds` tracks a player. */
internal val VideoAd.pauseFollowsContent: Boolean get() = format == AdFormat.PAUSE && owner.get()?.playback?.isTracking == true

/**
 * The app code a `show` runs before its checks: an overlay over stopped content ends (its `onAdEnded`), and
 * the player is read. Returns whether this overlay's content is paused.
 */
internal fun VideoAd.runAppCodeBeforeShow(): Boolean {
    if (!wasShown) Sdk.adOnScreen?.endIfContentStopped()
    if (!followsContent || owner.get()?.playback?.isPlaying != false) return false
    Logger.warning { "ad ${id.take(8)}: cannot show the overlay, the content is paused" }
    return true
}

/**
 * The overlay is on screen: its duration counts from the playhead now, and again from it whenever its window
 * comes back. A pause ad notes whether the content is paused now, so it ends once it plays after a pause.
 */
internal fun VideoAd.startFollowingContent() {
    if (pauseFollowsContent) {
        contentWasPaused = owner.get()?.playback?.isPlaying == false
        return
    }
    val duration = durationMillis ?: return
    val playback = owner.get()?.playback ?: return
    if (!followsContent) return
    contentProgress = ContentProgress(duration, playback.playheadMillis)
    clock?.onActivate = { contentProgress?.resync(playback.playheadMillis) }
}

/** The tracked player changed or a tick came. A pause while the overlay's view is being attached ends it before any impression. */
internal fun VideoAd.contentDidChange(change: ContentPlayback.Change) {
    val playback = owner.get()?.playback ?: return
    if (pauseFollowsContent) return pauseContentDidChange(change, playback)
    if (!followsContent || !wasShown || hasEnded) return
    if (change == ContentPlayback.Change.Jumped) {
        contentProgress?.resync(playback.playheadMillis)
        return
    }
    if (change == ContentPlayback.Change.ContentChanged) {
        Logger.info { "ad ${id.take(8)}: the content ended or changed; the overlay ends" }
        end(EndReason.INTERRUPTED)
        return
    }
    if (change is ContentPlayback.Change.Playback && change.isPlaying == false || !playback.isPlaying) {
        Logger.info { "ad ${id.take(8)}: the content paused or its player is gone; the overlay ends" }
        end(EndReason.INTERRUPTED)
        return
    }
    val progress = contentProgress ?: return
    if (change != ContentPlayback.Change.Tick) return
    if (progress.advance(playback.playheadMillis, counting = clock?.isCounting == true, speed = playback.playbackSpeed)) {
        Logger.debug { "ad ${id.take(8)}: overlay duration of ${progress.durationMillis} ms of video elapsed" }
        isCompleted = true
        end(EndReason.COMPLETED)
    }
}

/** An overlay over content that no longer plays ends: before another ad's `show`, and once its own view is attached. */
internal fun VideoAd.endIfContentStopped() {
    if (format != AdFormat.OVERLAY || !wasShown || hasEnded || !followsContent) return
    if (owner.get()?.playback?.isPlaying != false) return
    Logger.info { "ad ${id.take(8)}: the content paused; the overlay ends" }
    end(EndReason.INTERRUPTED)
}

/** A pause ad ends when the tracked content plays again: a listener's call to playing, or a tick that sees it play after a pause. */
private fun VideoAd.pauseContentDidChange(change: ContentPlayback.Change, playback: ContentPlayback) {
    if (!wasShown || hasEnded) return
    val playing = when (change) {
        is ContentPlayback.Change.Playback -> change.isPlaying ?: playback.isPlaying
        ContentPlayback.Change.Tick -> playback.isPlaying
        else -> return
    }
    if (!playing) {
        contentWasPaused = true
        return
    }
    // A listener's call is the player saying it plays again; a tick only reads a state, which counts after a pause.
    if (change is ContentPlayback.Change.Tick && !contentWasPaused) return
    Logger.info { "ad ${id.take(8)}: the content plays again; the pause ad ends" }
    end(EndReason.INTERRUPTED)
}
