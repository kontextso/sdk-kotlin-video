package so.kontext.video.internal.frame

import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.VideoAdException
import so.kontext.video.closeOutcome
import so.kontext.video.internal.diagnostics.ErrorReport
import so.kontext.video.internal.diagnostics.ErrorReporter
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.platform.Environment
import so.kontext.video.internal.platform.LinkOpener

// What the frame page reports, as beacons, the ad's state and the listener: VideoAd's half of the frame
// protocol, the bridge (`FrameBridge`) being the other.

/**
 * Every message of the page goes through here: [handleNow] with the content playhead read once first, when any URL
 * of the moment asks for it (`[CONTENTPLAYHEAD]`), so every beacon of one event carries the same time and the app's
 * player is asked once. The tracking URLs came from the ad server, and the tracker knows by itself whether one asks.
 * A click is the one event that brings URLs of its own, the click-through and its fallback, which the SDK fills in
 * too (`Tracker.clickThrough`): they are passed so a macro in them alone is found. `Open`'s URL is opened as it
 * is, with no macros, so it needs nothing.
 */
internal fun VideoAd.handle(event: FrameEvent) {
    val urls = if (event is FrameEvent.Click) listOfNotNull(event.url, event.fallbackUrl) else emptyList()
    withContentPlayhead(urls) { handleNow(event) }
}

// One branch per event of the frame protocol.
@Suppress("CyclomaticComplexMethod")
internal fun VideoAd.handleNow(event: FrameEvent) {
    if (hasEnded || invalidationReason != null) return
    when (event) {
        FrameEvent.Loaded -> {
            state.markLoaded()
            tracker.record(event)
            loadSignal?.complete(Unit)
            loadSignal = null
        }
        is FrameEvent.Error -> {
            tracker.record(event)
            reportCreativeError(event.code)
            val signal = loadSignal
            when {
                signal != null -> {
                    loadSignal = null
                    Logger.warning { "ad ${id.take(8)}: creative failed to load, VAST error ${event.code}" }
                    signal.completeExceptionally(VideoAdException.CreativeFailed(event.code))
                }
                wasShown -> {
                    Logger.warning { "ad ${id.take(8)}: creative failed after show, VAST error ${event.code}" }
                    fail(VideoAdException.CreativeFailed(event.code))
                }
                // Died while waiting to be shown: the ad ends; `show` will throw.
                else -> invalidate(InvalidationReason.CreativeFailed(event.code))
            }
        }
        is FrameEvent.Click -> click(event.url, event.fallbackUrl, event.area)
        is FrameEvent.Open -> {
            Logger.info { "ad ${id.take(8)}: open (${event.purpose}) ${event.url}" }
            tracker.record(event)
            Environment.context?.let { context ->
                if (!LinkOpener.open(context, event.url, null)) Logger.warning { "ad ${id.take(8)}: nothing could open the click-through ${event.url}" }
            }
        }
        is FrameEvent.Close -> closeByPage(event.reason)
        is FrameEvent.Skip -> {
            tracker.record(event)
            end(EndReason.SKIPPED)
        }
        is FrameEvent.Video -> {
            when (event.event) {
                FrameEvent.VideoEvent.START, FrameEvent.VideoEvent.RESUME -> isVideoPlaying = true
                FrameEvent.VideoEvent.PAUSE -> isVideoPlaying = false
                else -> Unit
            }
            meter?.countsTime = isVideoPlaying
            tracker.record(event)
            // The listener last: it may end the ad on the spot.
            if (event.event == FrameEvent.VideoEvent.START) recordImpression()
        }
        is FrameEvent.Progress -> tracker.record(event)
        is FrameEvent.Complete -> {
            state.markCompleted()
            isVideoPlaying = false
            meter?.countsTime = false
            tracker.record(event)
            Logger.info { "ad ${id.take(8)}: complete" }
        }
    }
}

/**
 * The page's own close control: how a page's close and skip end an ad, and why a linear ad's close can be a skip.
 *
 * ```
 *   what the viewer does (linear ad)        message        result
 *   taps Skip, after the skip offset        ad.skip        `skip` beacon, SKIPPED
 *   taps Close before the video finished    ad.close       `skip` beacon, SKIPPED
 *   closes the end card after the video     ad.close       `close` beacon, CLOSED
 *   nothing: the video ended, no end card   ad.close       COMPLETED, no `close` (nobody closed anything)
 * ```
 *
 * For a video ad what counts is whether the viewer watched it to the end. Leaving early is a skip, through
 * Skip or through Close alike, as video ad tracking counts it; `close` is for closing what already finished.
 * `isCompleted` is set only by the page's `complete` event, never by a close. Overlay and pause ads have no
 * "watch to the end": a close is CLOSED, or RESUMED when it resumed the content.
 */
internal fun VideoAd.closeByPage(reason: FrameEvent.CloseReason) {
    // Whether the ad is linear is a fact of the format, not of its screen.
    val outcome = closeOutcome(reason, isLinear = format == AdFormat.LINEAR, isCompleted = isCompleted)
    // The page ended itself because its creative ran to its end.
    if (outcome.completesAd) state.markCompleted()
    outcome.beacon?.let { tracker.fire(it) }
    end(outcome.endReason)
}

internal fun VideoAd.reportCreativeError(vastCode: Int) {
    val code = when (loadedBridge?.failureCause ?: FrameBridge.FailureCause.PAGE) {
        FrameBridge.FailureCause.PAGE -> ErrorReport.Code.CREATIVE_FAILED
        FrameBridge.FailureCause.LOAD_TIMEOUT -> ErrorReport.Code.LOAD_TIMEOUT
        FrameBridge.FailureCause.LOAD_FAILED -> ErrorReport.Code.FRAME_LOAD_FAILED
        FrameBridge.FailureCause.PROCESS_TERMINATED -> ErrorReport.Code.PROCESS_TERMINATED
        FrameBridge.FailureCause.UNRESPONSIVE -> ErrorReport.Code.UNRESPONSIVE
    }
    ErrorReporter.report(
        code,
        if (wasShown) ErrorReport.Stage.PLAYBACK else ErrorReport.Stage.LOAD,
        sessionId = owner.get()?.sessionId,
        adId = id,
        vastCode = vastCode,
        underlying = loadedBridge?.failureUnderlying,
        recovery = if (wasShown) ErrorReport.Recovery.AD_ENDED_FAILED else ErrorReport.Recovery.AD_DROPPED_BEFORE_SHOW,
    )
}
