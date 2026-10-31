package so.kontext.video

import android.content.Intent
import android.graphics.Rect
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import so.kontext.video.internal.rendering.ViewabilityMeter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exposure on real views, where rotation and clipping are the platform's own. */
@RunWith(AndroidJUnit4::class)
class ViewabilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun aRotatedClippingParentLeavingFortyFivePercentIsNotViewable() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, TestHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as TestHostActivity
        val laidOut = CountDownLatch(1)
        var exposure: Pair<Double, Rect>? = null
        try {
            instrumentation.runOnMainSync {
                val root = FrameLayout(activity)
                val clip = FrameLayout(activity).apply { rotation = 45f } // clips its children, as a ViewGroup does by default
                val creative = FrameLayout(activity)
                root.addView(
                    clip,
                    FrameLayout.LayoutParams(200, 200).apply {
                        leftMargin = 60
                        topMargin = 80
                    },
                )
                // 110 px in: 90 of its 200 px width stay inside the clipping parent.
                clip.addView(creative, FrameLayout.LayoutParams(200, 200).apply { leftMargin = 110 })
                root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (creative.width == 0) return
                        root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        exposure = ViewabilityMeter.exposure(creative)
                        laidOut.countDown()
                    }
                })
                activity.setContentView(root)
            }
            assertTrue("laid out", laidOut.await(10, TimeUnit.SECONDS))
            assertEquals(0.45, exposure!!.first, 0.015)
            // The visible part in the creative's own coordinates, give or take the pixel a rotated edge rounds to.
            val visible = exposure!!.second
            assertTrue("visible part $visible", visible.left == 0 && visible.top == 0 && visible.right in 90..91 && visible.bottom == 200)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
