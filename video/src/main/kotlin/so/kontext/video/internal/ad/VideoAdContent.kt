package so.kontext.video.internal.ad

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.VideoAdException
import so.kontext.video.internal.Sdk
import so.kontext.video.internal.playback.ContentPlayback
import so.kontext.video.internal.playback.ContentProgress
import so.kontext.video.internal.support.ActivityTracker
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger

// An overlay lives only while the content plays, when the `VideoAds` tracks a player: a pause, the end of
// the content, another player or a player that is gone ends it as INTERRUPTED; its duration counts
// milliseconds of video; and it cannot be shown over paused content. Without a tracked player it runs on
// its own time. A pause while the app is in the background (apps pause their player in `onStop`) only freezes
// it, as the background freezes its clock: back in front, it goes on if the content plays again within
// `VideoAd.returnGraceMillis`, and ends as INTERRUPTED otherwise. Another screen of the app over it is not the
// background: it ends the overlay (see `VideoAd.followHoldingActivity`), and so does a pause there.
//
// A pause ad lives only while the content is paused, when the `VideoAds` tracks a player: the content playing
// again ends it as INTERRUPTED, wherever the viewer resumed (the media notification, the lock screen, a
// headset, the picture-in-picture window); RESUMED stays the viewer's tap on the ad's own Resume. Only a
// return to playing after a pause seen while it is on screen counts: a pause ad shown a moment before the app
// pauses the player stays. The background needs no rule of its own here: an app pausing its player in
// `onStop` changes nothing for content already paused, and content playing again, on the return or in the
// background, ends the pause ad as it should.

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
 * An episode's ads only in that episode: one requested for it is counted for it. An overlay or pause ad whose
 * episode no longer plays in the tracked player (another `VideoAds` took it, or it plays another item) ends
 * before show, with no beacon, and `show` throws. A linear ad covers the content: not checked.
 */
internal fun VideoAd.checkEpisode() {
    if (format == AdFormat.LINEAR || owner.get()?.playback?.playsAnotherEpisode != true) return
    Logger.warning { "ad ${id.take(8)}: cannot show, its episode no longer plays in the tracked player; ended as ContentChanged" }
    invalidate(InvalidationReason.ContentChanged)
    throw VideoAdException.Invalidated(InvalidationReason.ContentChanged)
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

/**
 * The tracked player changed or a tick came. A pause while the overlay's view is being attached ends it before
 * any impression; one while it is away waits for the return (see [waitsOutPause]).
 */
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
    if (!contentPlays(change, playback)) return
    val progress = contentProgress ?: return
    if (change != ContentPlayback.Change.Tick) return
    if (progress.advance(playback.playheadMillis, counting = clock?.isCounting == true, speed = playback.playbackSpeed)) {
        Logger.debug { "ad ${id.take(8)}: overlay duration of ${progress.durationMillis} ms of video elapsed" }
        isCompleted = true
        end(EndReason.COMPLETED)
    }
}

/**
 * Whether the overlay's content plays. Paused, or its player gone, the overlay ends, unless the pause came
 * while it is away ([waitsOutPause]); playing, in the background or back in front, a pause seen away is over.
 */
private fun VideoAd.contentPlays(change: ContentPlayback.Change, playback: ContentPlayback): Boolean {
    if (change is ContentPlayback.Change.Playback && change.isPlaying == false || !playback.isPlaying) {
        if (waitsOutPause()) return false
        Logger.info { "ad ${id.take(8)}: the content paused or its player is gone; the overlay ends" }
        end(EndReason.INTERRUPTED)
        return false
    }
    contentPausedAway = false
    returnCheck?.cancel()
    returnCheck = null
    return true
}

/**
 * An ad over content that no longer fits it ends: before another ad's `show`, so an overlay shown right after
 * the content plays again finds the screen free of the pause ad (a player without a listener says so only on
 * the next tick); and, for an overlay, once its own view is attached, for a pause no callback reported while
 * that ran.
 */
internal fun VideoAd.endIfContentStopped() {
    if (format == AdFormat.PAUSE) {
        val playback = owner.get()?.playback ?: return
        if (!pauseFollowsContent || !isPresented || hasEnded) return
        return endIfContentResumed(playback.isPlaying)
    }
    if (format != AdFormat.OVERLAY || !wasShown || hasEnded || !followsContent) return
    if (owner.get()?.playback?.isPlaying != false) return
    // Away, the overlay only freezes; back in front, another ad's show needs the screen, the grace or not.
    if (isAway) {
        contentPausedAway = true
        return
    }
    Logger.info { "ad ${id.take(8)}: the content paused; the overlay ends" }
    end(EndReason.INTERRUPTED)
}

/**
 * A pause ad ends when the tracked content plays again after it was seen paused (a listener's call or a tick), and
 * at once when its episode is gone from the player: another VideoAds took the player (the next page of a feed
 * sharing one player) or it plays another item. Shown on, it would hold the app's one slot over content it is not for.
 */
private fun VideoAd.pauseContentDidChange(change: ContentPlayback.Change, playback: ContentPlayback) {
    if (!wasShown || hasEnded) return
    val playing = when (change) {
        ContentPlayback.Change.ContentChanged -> {
            Logger.info { "ad ${id.take(8)}: the content changed or another VideoAds took the player; the pause ad ends" }
            return end(EndReason.INTERRUPTED)
        }
        is ContentPlayback.Change.Playback -> change.isPlaying ?: playback.isPlaying
        ContentPlayback.Change.Tick -> playback.isPlaying
        else -> return
    }
    endIfContentResumed(playing)
}

/**
 * Content seen playing after it was seen paused while the pause ad is on screen ends it. A listener's call to
 * playing counts only after a pause too: a resume queued behind the pause that ended an overlay, delivered
 * once that overlay's `onAdEnded` showed this pause ad over playing content, is no resume of this one.
 */
private fun VideoAd.endIfContentResumed(playing: Boolean) {
    if (!playing) {
        contentWasPaused = true
        return
    }
    if (!contentWasPaused) return
    Logger.info { "ad ${id.take(8)}: the content plays again; the pause ad ends" }
    end(EndReason.INTERRUPTED)
}

/**
 * The overlay is away: the app is in the background, or on its way there (the Activity holding it paused and
 * no Activity of the app resumed: a player paused in `onStop`, or at the lifecycle's ON_STOP, pauses before
 * the SDK hears the stop). Another Activity of the app over it is resumed, so a pause then is no pause away.
 */
private val VideoAd.isAway: Boolean
    get() {
        if (!Environment.isAppInForeground()) return true
        val holder = holdingActivity?.get() ?: return false
        return ActivityTracker.isResumed(holder) == false && !ActivityTracker.hasResumedActivity
    }

/**
 * A pause the overlay does not end on now: one seen while away, which freezes it, or any while it waits for the
 * check after the return. Back in front with no check under way (the app came back without the holding
 * Activity resuming, which arms it), the check starts here, so a pause seen away never keeps it forever.
 */
private fun VideoAd.waitsOutPause(): Boolean {
    if (isAway) {
        if (!contentPausedAway) Logger.info { "ad ${id.take(8)}: the content paused in the background; the overlay waits for the return" }
        contentPausedAway = true
        return true
    }
    if (!contentPausedAway) return false
    if (returnCheck == null) checkAfterReturn()
    return true
}

/** The Activity holding the ad resumed: an overlay whose content paused while away checks it again after the grace. */
internal fun VideoAd.holdingActivityResumed() {
    if (contentPausedAway) checkAfterReturn()
}

/**
 * [VideoAd.returnGraceMillis] after the return, the content decides: playing, the overlay goes on where it
 * stopped; still paused, it ends as INTERRUPTED, as on any pause. Away again by then, the next return checks.
 */
private fun VideoAd.checkAfterReturn() {
    returnCheck?.cancel()
    returnCheck = Environment.scope.launch {
        delay(VideoAd.returnGraceMillis)
        returnCheck = null
        if (hasEnded || !contentPausedAway || isAway) return@launch
        contentPausedAway = false
        if (!followsContent || owner.get()?.playback?.isPlaying != false) return@launch
        Logger.info { "ad ${id.take(8)}: the content is still paused after the return; the overlay ends" }
        end(EndReason.INTERRUPTED)
    }
}
