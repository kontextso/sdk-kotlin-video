package so.kontext.video.internal.diagnostics

/**
 * The last [HISTORY_LENGTH] debug events, kept on the device for the history an error report carries; no
 * Android, no sending. [DebugReporter] records into it, [ErrorReport] reads [recent].
 */
internal object EventHistory {
    const val HISTORY_LENGTH = 30

    /** The state keys an error report's history may carry; anything else stays on the device. */
    val historyKeys = setOf("held", "playhead", "format", "filled", "reason", "enabled", "code", "durationMs", "step", "domain")

    private val moments = ArrayDeque<Moment>()

    /** One event in the history an error report carries. */
    class Moment(val createdAt: String, val name: String, val adId: String?, val state: Map<String, Any>?)

    /** Keeps one event; the oldest goes past [HISTORY_LENGTH]. */
    fun record(createdAt: String, name: String, adId: String?, state: Map<String, Any>?) {
        moments.addLast(Moment(createdAt, name, adId, state))
        while (moments.size > HISTORY_LENGTH) moments.removeFirst()
    }

    /** The recent events, reduced for an error report: listed state keys only, strings and ids capped. */
    fun recent(): List<Moment> = moments.map { moment ->
        val state = moment.state?.filterKeys { it in historyKeys }?.mapValues { (_, value) ->
            if (value is String) ReportFormat.capped(value) else value
        }
        Moment(moment.createdAt, ReportFormat.capped(moment.name), moment.adId?.let(ReportFormat::capped), state?.takeIf { it.isNotEmpty() })
    }

    fun clear() {
        moments.clear()
    }
}
