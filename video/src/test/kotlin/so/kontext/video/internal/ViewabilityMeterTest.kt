package so.kontext.video.internal

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun aStallStartsTheDwellAgain() {
        ViewabilityMeter.intervalMillis = 250
        val meter = meter(laidOutView(), requiredMillis = 1_000)
        meter.start() // a tick at 0
        now = 250
        meter.tick()
        now = 500
        meter.tick()
        now = 1_500 // a second with no tick: the app froze, nobody measured
        meter.tick()
        for (at in listOf(1_750L, 2_000L, 2_250L)) {
            now = at
            meter.tick()
        }
        assertTrue("the stretch began again at 1 500", verdicts.isEmpty())
        now = 2_500
        meter.tick()
        assertEquals(listOf(Verdict.VIEWABLE), verdicts)
        meter.stop()
        assertEquals("decided once", listOf(Verdict.VIEWABLE), verdicts)
    }

    @Test fun aViewDetachedForOneTickIsGivenGrace() {
        val view = laidOutView()
        var detached = 0
        val meter = meter(view).apply { onDetached = { detached += 1 } }
        meter.start()
        (view.parent as ViewGroup).removeView(view)
        meter.tick()
        assertEquals("a view moved within the same moment is not gone", 0, detached)
        meter.tick()
        assertEquals(1, detached)
    }

    @Test fun offScreenCountsOnlyWhileActive() {
        ViewabilityMeter.offScreenGraceMillis = 1_000
        val view = laidOutView()
        var active = false
        var offScreen = 0
        val meter = meter(view).apply {
            isActive = { active }
            onOffScreen = { offScreen += 1 }
        }
        meter.start()
        view.translationX = 10_000f // scrolled away
        repeat(10) {
            now += 500
            meter.tick()
        }
        assertEquals("in the background nothing ends", 0, offScreen)
        active = true
        meter.tick()
        now += 999
        meter.tick()
        assertEquals(0, offScreen)
        now += 1
        meter.tick()
        assertEquals(1, offScreen)
    }

    @Test fun nothingIsReportedBeforeTheFirstLayout() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = FrameLayout(activity)
        activity.setContentView(view) // attached, not laid out yet
        val exposures = mutableListOf<Double>()
        val meter = meter(view).apply { onExposure = { share, _ -> exposures += share } }
        meter.start()
        meter.tick()
        assertTrue("a 0 for want of a layout would pause the page's video: $exposures", exposures.isEmpty())
        meter.stop()
    }
}
