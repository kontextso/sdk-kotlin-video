package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.JsonObject

/**
 * The error reports waiting to be sent, and the batches made from them; no coroutines, no Android, no sending.
 * At most [MAX_BUFFERED] reports and [MAX_BUFFERED_BYTES] wait: past either the oldest go. A batch takes the
 * oldest reports, at most [MAX_BATCH], whose whole body stays within [MAX_BATCH_BYTES]; a report that does not
 * fit even alone goes.
 */
internal class ReportQueue {
    private val buffer = ArrayDeque<Pair<JsonObject, Int>>()
    private var bufferedBytes = 0

    /** Reports waiting. */
    val size: Int get() = buffer.size

    /** The reports waiting, oldest first. */
    val reports: List<JsonObject> get() = buffer.map { it.first }

    /** The encoded sizes of the reports waiting. */
    val sizes: List<Int> get() = buffer.map { it.second }

    /** One batch: its finished body and how many reports it holds. Retried as it is; the server counts a retry again. */
    class Batch(val body: JsonObject, val count: Int)

    fun isEmpty(): Boolean = buffer.isEmpty()

    /** Queues [report]; one over [MAX_BATCH_BYTES] alone goes instead. Says whether it was queued. */
    fun add(report: JsonObject): Boolean {
        val size = report.toString().toByteArray().size
        // Ids and codes are capped; the history's console lines are not, so a report can in theory outgrow a
        // batch. Then the report goes rather than the budgets.
        if (size > MAX_BATCH_BYTES) return false
        buffer.addLast(report to size)
        bufferedBytes += size
        while (buffer.size > MAX_BUFFERED || (bufferedBytes > MAX_BUFFERED_BYTES && buffer.size > 1)) {
            Logger.debug { "oldest error report dropped: ${buffer.size} waiting, $bufferedBytes bytes" }
            bufferedBytes -= buffer.removeFirst().second
        }
        return true
    }

    /** Everything goes. */
    fun clear() {
        buffer.clear()
        bufferedBytes = 0
    }

    /**
     * The next batch: the oldest reports whose whole body, [envelope] (around the reports) and separators
     * included, stays within [MAX_BATCH_BYTES]; the rest wait for the next one. A report that cannot fit even
     * alone goes. `null` when nothing is left to send.
     */
    fun makeBatch(envelope: (reports: List<JsonObject>) -> JsonObject): Batch? {
        var bytes = envelope(emptyList()).toString().toByteArray().size
        val reports = mutableListOf<JsonObject>()
        while (buffer.isNotEmpty() && reports.size < MAX_BATCH) {
            val (report, size) = buffer.first()
            val added = size + if (reports.isEmpty()) 0 else 1 // the comma before it
            if (bytes + added > MAX_BATCH_BYTES && reports.isNotEmpty()) break
            buffer.removeFirst()
            bufferedBytes -= size
            if (bytes + added > MAX_BATCH_BYTES) {
                Logger.debug { "error report of $size bytes dropped, it cannot fit a batch" }
                continue
            }
            reports += report
            bytes += added
        }
        if (reports.isEmpty()) return null
        return Batch(envelope(reports), reports.size)
    }

    companion object {
        const val MAX_BUFFERED = 200
        const val MAX_BATCH = 50
        const val MAX_BUFFERED_BYTES = 256 * 1024
        const val MAX_BATCH_BYTES = 64 * 1024
    }
}
