package so.kontext.video.tracking

import android.graphics.Rect
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * MRC display viewability for a native ad view: at least 50 % of the ad's
 * pixels on screen for one continuous second, timed (not waited for), while
 * the window is visible. Measurement starts only after the impression.
 */
internal class ViewabilityMeter(
    private val view: View,
    private val scope: CoroutineScope,
    private val onVerdict: (AdTracker.Event) -> Unit,
) {
    private var job: Job? = null
    private var decided = false

    /** Call once the image is painted. Arms the one-second clock; a lapse in coverage voids the accrued stretch. */
    fun start() {
        if (job != null || decided) return
        job = scope.launch {
            var coveredMs = 0L
            while (isActive) {
                coveredMs = if (isVisibleEnough()) coveredMs + STEP_MS else 0
                if (coveredMs >= VIEWABLE_MS) {
                    decide(AdTracker.Event.VIEWABLE)
                    return@launch
                }
                delay(STEP_MS)
            }
        }
    }

    /** Call when the ad leaves the screen. Undecided → `NOT_VIEWABLE`. */
    fun end() {
        job?.cancel()
        job = null
        decide(AdTracker.Event.NOT_VIEWABLE)
    }

    private fun decide(verdict: AdTracker.Event) {
        if (decided) return
        decided = true
        job?.cancel()
        job = null
        onVerdict(verdict)
    }

    private fun isVisibleEnough(): Boolean {
        if (!view.isShown || view.windowVisibility != View.VISIBLE || view.alpha < 0.01f) return false
        if (view.width <= 0 || view.height <= 0) return false
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return false
        val ratio = visible.width().toFloat() * visible.height() / (view.width.toFloat() * view.height)
        return ratio >= VIEWABLE_RATIO
    }

    companion object {
        const val VIEWABLE_RATIO: Float = 0.5f
        const val VIEWABLE_MS: Long = 1_000
        private const val STEP_MS: Long = 100
    }
}
