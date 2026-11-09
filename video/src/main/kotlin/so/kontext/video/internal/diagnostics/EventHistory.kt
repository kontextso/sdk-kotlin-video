package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.JsonObject

/**
 * The last [HISTORY_LENGTH] debug events, kept on the device for the history an error report carries; no
 * Android, no sending. [DebugReporter] records into it, [ErrorReport] reads [recent]. An entry is the event as it
 * would go to `/debug`, so the two read the same.
 */
internal object EventHistory {
    const val HISTORY_LENGTH = 30

    private val events = ArrayDeque<JsonObject>()

    /** Keeps one event; the oldest goes past [HISTORY_LENGTH]. */
    fun record(event: JsonObject) {
        events.addLast(event)
        while (events.size > HISTORY_LENGTH) events.removeFirst()
    }

    /** The recent events, oldest first, as kept. */
    fun recent(): List<JsonObject> = events.toList()

    fun clear() {
        events.clear()
    }
}
