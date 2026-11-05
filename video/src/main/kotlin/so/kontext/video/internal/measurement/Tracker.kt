package so.kontext.video.internal.measurement

import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.frame.FrameEvent
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.Environment
import java.util.UUID

/**
 * Fires an ad's tracking entries when their moment happens, once each except the events that repeat, with
 * the macros substituted ([TrackingMacros]). The page never fires a beacon; the SDK fires every one. No retries.
 */
internal class Tracker(private val adId: String, private val entries: List<AdPayload.TrackingEvent>) {
    /** Milliseconds into the ad video, as the page last reported it; `[ADPLAYHEAD]`. */
    var adPlayheadMillis = 0L

    /** Content time in milliseconds; `[MEDIAPLAYHEAD]`. The app's player behind it is app code, read once per operation. */
    var contentPlayhead: () -> Long = { 0 }

    /** The content playhead read before the operation now running; every beacon of it uses this value. */
    private var frozenContentPlayhead: Long? = null

    /** The last value the app's player gave. */
    private var lastContentPlayhead = 0L

    /** Whether any URL asks for the content playhead; when none does, nobody reads it. */
    val readsContentPlayhead: Boolean = entries.any { TrackingMacros.asksForContentPlayhead(it.url) }

    private val fired = mutableSetOf<Int>()

    /** Why the SDK did not use a bid, for the `notUsed` beacon. */
    sealed class NotUsed(val reason: String, val winningPrice: Double? = null, val winner: String? = null) {
        object NotUsedByApp : NotUsed("notUsed")
        class Outbid(winningPrice: Double?, winner: String?) : NotUsed("outbid", winningPrice, winner)
        object Destroyed : NotUsed("destroyed")
    }

    /** Whether the ad has an entry named [event] for [part]. */
    fun has(event: String, part: String?): Boolean = entries.any { it.event == event && it.part == part }

    /**
     * Fires every entry named [event] for [part] (`null` is the main creative) that has not fired yet. Returns
     * the occurrence's fire id, for a click-through that must carry the same one.
     */
    fun fire(
        event: String,
        part: String? = null,
        offsetMillis: Long? = null,
        errorCode: Int? = null,
        viewDurationMillis: Long? = null,
        notUsed: NotUsed? = null,
    ): String {
        // One fire id per occurrence, shared by every URL of it: the server dedupes on (adId, event, fid).
        val fireId = UUID.randomUUID().toString()
        entries.forEachIndexed { index, entry ->
            if (index in fired || entry.event != event || entry.part != part) return@forEachIndexed
            if (offsetMillis != null && entry.offsetMillis != offsetMillis) return@forEachIndexed
            if (event !in REPEATABLE) fired += index
            send(substitute(entry.url, errorCode, fireId, viewDurationMillis, notUsed), part?.let { "$event ($it)" } ?: event)
        }
        return fireId
    }

    /** Fires every `progress` entry the ad video has passed and not fired yet, each as its own occurrence, in order. */
    fun fireProgress(upToMillis: Long) {
        val due = entries.indices.mapNotNull { index ->
            val entry = entries[index]
            if (entry.event == "progress" && index !in fired && entry.offsetMillis != null && entry.offsetMillis <= upToMillis) entry.offsetMillis else null
        }.toSortedSet()
        due.forEach { fire("progress", offsetMillis = it) }
    }

    /**
     * Fires the beacons of a page event, after the page's playhead is recorded for `[ADPLAYHEAD]`. Each video
     * moment also catches up the `progress` entries its playhead passed, so a checkpoint the page never reported
     * still fires. A click and a close fire nothing here: the ad fires them, a click's fire id going into its
     * click-through and a close's beacon depending on what the ad already saw (`closeOutcome`). It takes a
     * [FrameEvent] because the beacons are named after the page's events (VAST names): which beacons an event
     * causes is tracking knowledge.
     *
     * ```
     *   loaded     loaded
     *   error      error, with the code
     *   open       iconClick, for an AdChoices open
     *   skip       progress caught up, then skip
     *   video      the moment's own (start, firstQuartile, ...), then progress caught up
     *   progress   progress up to the greater of the offset and the playhead
     *   complete   progress caught up, complete, then creativeView of the end card (visible now)
     * ```
     */
    fun record(event: FrameEvent) {
        when (event) {
            FrameEvent.Loaded -> fire("loaded")
            is FrameEvent.Error -> fire("error", errorCode = event.code)
            is FrameEvent.Open -> if (event.purpose == "adChoices") fire("iconClick")
            is FrameEvent.Skip -> {
                adPlayheadMillis = event.playheadMillis
                fireProgress(event.playheadMillis)
                fire("skip")
            }
            is FrameEvent.Video -> {
                adPlayheadMillis = event.playheadMillis
                fire(event.event.wire)
                fireProgress(event.playheadMillis)
            }
            is FrameEvent.Progress -> {
                adPlayheadMillis = event.playheadMillis
                fireProgress(maxOf(event.offsetMillis, event.playheadMillis))
            }
            is FrameEvent.Complete -> {
                adPlayheadMillis = event.playheadMillis
                fireProgress(event.playheadMillis)
                fire("complete")
                // The end card, when there is one, becomes visible now.
                fire("creativeView", part = "endCard")
            }
            is FrameEvent.Click, is FrameEvent.Close -> Unit
        }
    }

    /** The click-through with the beacon macros substituted, `[KONTEXT_FIREID]` with the click's own fire id. */
    fun clickThrough(url: String, fireId: String): String = substitute(url, null, fireId, null, null)

    /**
     * Runs [work] with the content playhead read once before it, so no beacon of it calls the app's player again
     * and every beacon of one operation carries the same time. Read only when a URL asks for it (the ad's own,
     * or [alsoFor], a click's URLs); a nested call keeps the outer reading.
     */
    fun <T> withContentPlayhead(alsoFor: List<String> = emptyList(), work: () -> T): T {
        val isAsked = readsContentPlayhead || alsoFor.any(TrackingMacros::asksForContentPlayhead)
        if (frozenContentPlayhead != null || !isAsked) return work()
        frozenContentPlayhead = lastContentPlayhead
        try {
            val playhead = contentPlayhead()
            lastContentPlayhead = playhead
            frozenContentPlayhead = playhead
            return work()
        } finally {
            frozenContentPlayhead = null
        }
    }

    private fun send(url: String, label: String) {
        Logger.debug { "ad ${adId.take(8)}: beacon $label" }
        Environment.sendBeacon(url, "ad ${adId.take(8)}: $label")
    }

    private fun substitute(template: String, errorCode: Int?, fireId: String, viewDurationMillis: Long?, notUsed: NotUsed?): String =
        TrackingMacros.substitute(
            template,
            contentPlayhead = { frozenContentPlayhead ?: contentPlayhead() },
            adPlayheadMillis = adPlayheadMillis,
            errorCode = errorCode,
            fireId = fireId,
            viewDurationMillis = viewDurationMillis,
            notUsed = notUsed,
        )

    companion object {
        /** Events that fire every time they happen; everything else fires once per entry. */
        val REPEATABLE = setOf("click", "pause", "resume", "mute", "unmute")
    }
}
