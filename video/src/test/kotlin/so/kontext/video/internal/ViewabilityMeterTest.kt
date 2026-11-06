package so.kontext.video.internal

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.measurement.ViewabilityMeter.Verdict
import so.kontext.video.internal.platform.Environment

/** The meter on its own, driven tick by tick with a frozen clock: the interval is too long to tick by itself. */
@RunWith(RobolectricTestRunner::class)
class ViewabilityMeterTest {
    private var now = 0L
    private val verdicts = mutableListOf<Verdict>()

    @Before fun setUp() {
        Environment.now = { now }
        ViewabilityMeter.intervalMillis = 1_000_000
    }

    @After fun tearDown() {
        ViewabilityMeter.intervalMillis = 250
        ViewabilityMeter.offScreenGraceMillis = 1_000
        Environment.reset()
    }

    /** A view laid out at 320×400 in a visible window. */
    private fun laidOutView(): View {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = FrameLayout(activity)
        activity.setContentView(view)
        view.layout(0, 0, 320, 400)
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(view, "mAttachInfo"), "mWindowVisibility", View.VISIBLE)
        return view
    }

    private fun meter(view: View, requiredMillis: Long = 1_000) =
        ViewabilityMeter(view, requiredMillis).apply { onVerdict = { verdicts += it } }

    @Test fun aViewNeverMeasuredIsUndeterminedNotNotViewable() {
        val meter = meter(View(Robolectric.buildActivity(Activity::class.java).setup().get()))
        meter.start()
        meter.tick()
        meter.stop()
        assertEquals(listOf(Verdict.VIEW_UNDETERMINED), verdicts)
    }

    @Test fun aMeasuredViewThatNeverReachedTheRuleIsNotViewable() {
        val view = laidOutView()
        val meter = meter(view)
        meter.start()
        now = 500
        meter.tick()
        meter.stop()
        assertEquals(listOf(Verdict.NOT_VIEWABLE), verdicts)
    }
}
