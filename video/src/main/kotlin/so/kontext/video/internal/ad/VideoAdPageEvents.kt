package so.kontext.video.internal.ad

import so.kontext.video.AdFormat
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.Click
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAd.InvalidationReason
import so.kontext.video.VideoAdException
import so.kontext.video.internal.rendering.FrameBridge
import so.kontext.video.internal.rendering.FrameEvent
import so.kontext.video.internal.support.ErrorReporter
import so.kontext.video.internal.support.Logger

// What the frame page reports, as beacons, the ad's state and the listener: VideoAd's half of the frame
// protocol, the bridge (`FrameBridge`) being the other.

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
            isLoaded = true
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
        is FrameEvent.Click -> {
            Logger.info { "ad ${id.take(8)}: click (${event.area}) ${Logger.describe(event.url)}" }
            // One occurrence: the beacon and the click-through carry the same fire id.
            // Every accepted click is counted: an end-card click on an ad without end-card entries (an overlay, a
            // pause ad) fires the main creative's.
            val endCard = event.area == Click.Area.EndCard && tracker.has("click", "endCard")
            val fireId = tracker.fire("click", part = if (endCard) "endCard" else null)
            open(tracker.clickThrough(event.url, fireId), event.fallbackUrl?.let { tracker.clickThrough(it, fireId) })
            if (hasEnded) return
            listener?.onAdClicked(this, Click(event.area))
        }
        is FrameEvent.Open -> {
            Logger.info { "ad ${id.take(8)}: open (${event.purpose}) ${Logger.describe(event.url)}" }
            if (event.purpose == "adChoices") tracker.fire("iconClick")
            open(event.url, null)
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
                FrameEvent.VideoEvent.START, FrameEvent.VideoEvent.RESUME -> videoPlaying = true
                FrameEvent.VideoEvent.PAUSE -> videoPlaying = false
                else -> Unit
            }
            meter?.countsTime = videoPlaying
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
            isCompleted = true
            videoPlaying = false
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
 * The page's own close control. A linear ad's close before `complete` is a skip; `complete` is the page
 * ending itself after the video with no end card: nobody closed anything, so no `close` fires.
 */
internal fun VideoAd.closeByPage(reason: FrameEvent.CloseReason) {
    if (format == AdFormat.LINEAR && !isCompleted) {
        tracker.fire("skip")
        end(EndReason.SKIPPED)
        return
    }
    when (reason) {
        FrameEvent.CloseReason.COMPLETE -> end(EndReason.COMPLETED)
        FrameEvent.CloseReason.RESUME -> {
            tracker.fire("close")
            end(EndReason.RESUMED)
        }
        FrameEvent.CloseReason.CLOSE -> {
            tracker.fire("close")
            end(EndReason.CLOSED)
        }
    }
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
