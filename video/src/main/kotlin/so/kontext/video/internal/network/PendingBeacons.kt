package so.kontext.video.internal.network

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import so.kontext.video.internal.diagnostics.Logger

/**
 * The outcome beacons of one `VideoAds` still on the wire (`notUsed` of a `reject()`, an `error`), which its next
 * ads request waits for, at most [WAIT_MILLIS].
 *
 * Why: the server serves a session's bid again until it has heard its outcome. `reject()` followed at once by
 * `format()` would race its own `notUsed` and get the rejected bid back, which the SDK then ignores as already
 * used: a no-fill the server never meant. The cap keeps a beacon route that never answers from holding ads back.
 *
 * ```
 *   reject()  ──► notUsed GET ─────────────── answered ─┐
 *   format()  ──► /config, the request built ── wait ───┴─► POST /video/ads
 *                                               (at most 2 s)
 * ```
 *
 * Main thread only, as the `VideoAds` that holds it.
 */
internal class PendingBeacons {
    private val jobs = mutableListOf<Job>()

    /** [job] is an outcome beacon on the wire; one already done is not kept. */
    fun add(job: Job?) {
        jobs.removeAll { it.isCompleted }
        if (job != null && !job.isCompleted) jobs += job
    }

    /** Waits until every outcome beacon sent so far has finished, or [WAIT_MILLIS] passed; never throws for a beacon. */
    suspend fun await() {
        val pending = jobs.filter { !it.isCompleted }
        jobs.clear()
        if (pending.isEmpty()) return
        val isDone = withTimeoutOrNull(WAIT_MILLIS) { pending.joinAll() } != null
        if (!isDone) Logger.info { "${pending.count { !it.isCompleted }} outcome beacon(s) still on the wire after $WAIT_MILLIS ms; the ads request goes" }
    }

    companion object {
        /** The longest an ads request waits for the outcome beacons before it. */
        const val WAIT_MILLIS = 2_000L
    }
}
