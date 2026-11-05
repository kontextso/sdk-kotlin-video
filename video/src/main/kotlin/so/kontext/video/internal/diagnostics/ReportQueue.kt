package so.kontext.video.internal.diagnostics

import kotlinx.serialization.json.JsonObject

/**
 * The error reports waiting to be sent, and the batches made from them; no coroutines, no Android, no sending.
 * At most [MAX_BUFFERED] reports and [MAX_BUFFERED_BYTES] wait: past either the oldest go, counted in
 * [dropped], which the next batch says. A batch takes the oldest reports, at most [MAX_BATCH], whose whole body
 * stays within [MAX_BATCH_BYTES]; a report that does not fit even alone is dropped and counted.
 */
internal class ReportQueue {
    private val buffer = ArrayDeque<Pair<JsonObject, Int>>()
    private var bufferedBytes = 0

    /** Reports dropped since the last batch: the next one says how many. */
    var dropped = 0
        private set

    /** Reports waiting. */
    val size: Int get() = buffer.size

    /** The reports waiting, oldest first. */
    val reports: List<JsonObject> get() = buffer.map { it.first }

    /** The encoded sizes of the reports waiting. */
    val sizes: List<Int> get() = buffer.map { it.second }

    /** One batch: its finished body and how many reports it holds. */
    class Batch(val body: JsonObject, val count: Int)

    fun isEmpty(): Boolean = buffer.isEmpty()

    /** Queues [report]; one over [MAX_BATCH_BYTES] alone is dropped and counted. Says whether it was queued. */
    fun add(report: JsonObject): Boolean {
        val size = report.toString().toByteArray().size
        if (size > MAX_BATCH_BYTES) {
            dropped += 1
            return false
        }
        buffer.addLast(report to size)
        bufferedBytes += size
        while (buffer.size > MAX_BUFFERED || (bufferedBytes > MAX_BUFFERED_BYTES && buffer.size > 1)) {
            bufferedBytes -= buffer.removeFirst().second
            dropped += 1
        }
        return true
    }

    /** Everything goes, the count of dropped ones too. */
    fun clear() {
        buffer.clear()
        bufferedBytes = 0
        dropped = 0
    }

    /**
     * The next batch: the oldest reports whose whole body, [envelope] (the reports and the dropped count) and
     * separators included, stays within [MAX_BATCH_BYTES]; the rest wait for the next one. `null` when nothing
     * is left to send. A batch made says the dropped count, which starts again from 0.
     */
    fun makeBatch(envelope: (reports: List<JsonObject>, dropped: Int) -> JsonObject): Batch? {
        // The empty envelope, its `dropped` as large as it can get while this batch is made.
        var bytes = envelope(emptyList(), dropped + buffer.size).toString().toByteArray().size
        val reports = mutableListOf<JsonObject>()
        while (buffer.isNotEmpty() && reports.size < MAX_BATCH) {
            val (report, size) = buffer.first()
            val added = size + if (reports.isEmpty()) 0 else 1 // the comma before it
            if (bytes + added > MAX_BATCH_BYTES && reports.isNotEmpty()) break
            buffer.removeFirst()
            bufferedBytes -= size
            if (bytes + added > MAX_BATCH_BYTES) {
                dropped += 1
                continue
            }
            reports += report
            bytes += added
        }
        if (reports.isEmpty()) return null
        return Batch(envelope(reports, dropped), reports.size).also { dropped = 0 }
    }

    companion object {
        const val MAX_BUFFERED = 200
        const val MAX_BATCH = 50
        const val MAX_BUFFERED_BYTES = 256 * 1024
        const val MAX_BATCH_BYTES = 64 * 1024
    }
}
