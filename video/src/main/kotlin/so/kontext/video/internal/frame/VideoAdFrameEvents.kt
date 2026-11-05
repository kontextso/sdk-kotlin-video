package so.kontext.video.internal.frame

import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.VideoAdException
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
            tracker.fire("loaded")
            loadSignal?.complete(Unit)
            loadSignal = null
        }
        is FrameEvent.Error -> {
            tracker.fire("error", errorCode = event.code)
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
            if (event.purpose == "adChoices") tracker.fire("iconClick")
            Environment.context?.let { context ->
                if (!LinkOpener.open(context, event.url, null)) Logger.warning { "ad ${id.take(8)}: nothing could open the click-through ${event.url}" }
            }
        }
        is FrameEvent.Close -> closeByPage(event.reason)
        is FrameEvent.Skip -> {
            tracker.adPlayheadMillis = event.playheadMillis
            tracker.fireProgress(event.playheadMillis)
            tracker.fire("skip")
            end(EndReason.SKIPPED)
        }
        is FrameEvent.Video -> {
            tracker.adPlayheadMillis = event.playheadMillis
            when (event.event) {
                FrameEvent.VideoEvent.START, FrameEvent.VideoEvent.RESUME -> isVideoPlaying = true
                FrameEvent.VideoEvent.PAUSE -> isVideoPlaying = false
                else -> Unit
            }
            meter?.countsTime = isVideoPlaying
            tracker.fire(event.event.wire)
            tracker.fireProgress(event.playheadMillis)
            // The listener last: it may end the ad on the spot.
            if (event.event == FrameEvent.VideoEvent.START) recordImpression()
        }
        is FrameEvent.Progress -> {
            tracker.adPlayheadMillis = event.playheadMillis
            tracker.fireProgress(maxOf(event.offsetMillis, event.playheadMillis))
        }
        is FrameEvent.Complete -> {
            tracker.adPlayheadMillis = event.playheadMillis
            state.markCompleted()
            isVideoPlaying = false
            meter?.countsTime = false
            tracker.fireProgress(event.playheadMillis)
            tracker.fire("complete")
            // The end card, when there is one, becomes visible now.
            tracker.fire("creativeView", part = "endCard")
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
    val outcome = reason.outcome(isLinear = format == AdFormat.LINEAR, isCompleted = isCompleted)
    // The page ended itself because its creative ran to its end.
    if (outcome.completesAd) state.markCompleted()
    outcome.beacon?.let { tracker.fire(it) }
    end(outcome.endReason)
}

internal fun VideoAd.reportCreativeError(vastCode: Int) {
    val code = when (loadedBridge?.failureCause ?: FrameBridge.FailureCause.PAGE) {
        FrameBridge.FailureCause.PAGE -> ErrorReporter.Code.CREATIVE_FAILED
        FrameBridge.FailureCause.LOAD_TIMEOUT -> ErrorReporter.Code.LOAD_TIMEOUT
        FrameBridge.FailureCause.LOAD_FAILED -> ErrorReporter.Code.FRAME_LOAD_FAILED
        FrameBridge.FailureCause.PROCESS_TERMINATED -> ErrorReporter.Code.PROCESS_TERMINATED
        FrameBridge.FailureCause.UNRESPONSIVE -> ErrorReporter.Code.UNRESPONSIVE
    }
    ErrorReporter.report(
        code,
        if (wasShown) ErrorReporter.Stage.PLAYBACK else ErrorReporter.Stage.LOAD,
        sessionId = owner.get()?.sessionId,
        adId = id,
        vastCode = vastCode,
        underlying = loadedBridge?.failureUnderlying,
        recovery = if (wasShown) ErrorReporter.Recovery.AD_ENDED_FAILED else ErrorReporter.Recovery.AD_DROPPED_BEFORE_SHOW,
    )
}
