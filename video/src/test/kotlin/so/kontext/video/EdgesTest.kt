package so.kontext.video

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import so.kontext.video.internal.Fixtures
import so.kontext.video.internal.ad.handle
import so.kontext.video.internal.rendering.FrameEvent
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.wire.AdPayload

/** The public API's corners: when an ad is ready, which size it takes, where a click lands, what equals what. */
@RunWith(RobolectricTestRunner::class)
class EdgesTest : SdkTest() {
    @Test fun anAdIsReadyOnlyLoadedUnshownAndValid() {
        var now = 0L
        Environment.now = { now }
        val owner = owner()
        val unloaded = VideoAd(AdPayload.parse(Fixtures.json(Fixtures.overlay())), 0, owner)
        assertFalse("not loaded", unloaded.isReady)
        val shown = overlay(owner)
        assertTrue(shown.isReady)
        shown.show(container())
        assertFalse("shown", shown.isReady)
        val rejected = overlay(owner)
        rejected.reject()
        assertFalse("rejected", rejected.isReady)
        val expiring = overlay(owner)
        now = 600_001
        assertFalse("expired", expiring.isReady)
    }

    @Test fun theBiggestVariantThatFitsUnscaledWinsElseTheOneShrunkLeast() {
        val density = Environment.context!!.resources.displayMetrics.density

        // The container is the screen's width, 320 dp: a wider one would reach under the screen's edge.
        fun chosen(widthDp: Int, vararg sizes: String): AdPayload.Size? {
            val ad = loaded(AdPayload.parse(Fixtures.json(Fixtures.overlay(sizes = sizes.joinToString(prefix = "[", postfix = "]")))), owner())
            ad.show(container((widthDp * density).toInt(), (300 * density).toInt()))
            return ad.loadedBridge!!.chosenSize.also { ad.dismiss() }
        }
        assertEquals(AdPayload.Size(300, 60), chosen(320, """{"width": 160, "height": 40}""", """{"width": 300, "height": 60}"""))
        assertEquals("one that fits beats a bigger one shrunk", AdPayload.Size(300, 60), chosen(320, """{"width": 400, "height": 100}""", """{"width": 300, "height": 60}"""))
        assertEquals("both shrink: the least shrunk", AdPayload.Size(400, 100), chosen(200, """{"width": 480, "height": 60}""", """{"width": 400, "height": 100}"""))
        assertEquals(
            "one may not shrink: the other, shrunk",
            AdPayload.Size(640, 160),
            chosen(320, """{"width": 480, "height": 80, "scalable": false}""", """{"width": 640, "height": 160}"""),
        )
    }

    @Test fun aClickOpensItsFallbackWhenNothingHandlesTheTarget() {
        val started = mutableListOf<String>()
        val app = Environment.context!!
        // The SDK keeps the application context: the wrapper stands in as one.
        Environment.context = object : ContextWrapper(app) {
            override fun getApplicationContext() = this

            override fun startActivity(intent: Intent) {
                when (intent.data?.scheme) {
                    "brandapp" -> throw ActivityNotFoundException()
                    "locked" -> throw SecurityException()
                    else -> started += intent.dataString!!
                }
            }
        }
        val ad = overlay()
        val recorder = Recorder()
        ad.listener = recorder
        ad.show(container())
        ad.handle(FrameEvent.Click("brandapp://product/1", "https://brand.test/product/1", VideoAd.Click.Area.Creative))
        assertEquals(1, started.size)
        assertTrue(started.single().startsWith("https://brand.test/product/1"))
        ad.handle(FrameEvent.Click("brandapp://product/2", "locked://x", VideoAd.Click.Area.from("cta")))
        ad.handle(FrameEvent.Click("locked://y", null, VideoAd.Click.Area.Creative))
        assertEquals("nothing could open those: nothing opened", 1, started.size)
        assertEquals("the viewer still clicked, each time", 3, recorder.clicks.size)
        assertEquals(VideoAd.Click.Area.Other("cta"), recorder.clicks[1].area)
        Environment.context = app
    }

    @Test fun theValueTypesDifferByEachField() {
        val price = VideoAd.Price(1.5, "USD", VideoAd.Price.Precision.EXACT)
        assertNotEquals(price, VideoAd.Price(2.0, "USD", VideoAd.Price.Precision.EXACT))
        assertNotEquals(price, VideoAd.Price(1.5, "EUR", VideoAd.Price.Precision.EXACT))
        assertNotEquals(price, "1.5 USD")
        assertEquals(price.hashCode(), VideoAd.Price(1.5, "USD", VideoAd.Price.Precision.EXACT).hashCode())
        val outbid = VideoAd.RejectReason.Outbid(2.0, "net")
        assertNotEquals(outbid, VideoAd.RejectReason.Outbid(3.0, "net"))
        assertNotEquals(outbid, VideoAd.RejectReason.Outbid(2.0, "other"))
        assertNotEquals(outbid, VideoAd.RejectReason.NotUsed)
        assertNotEquals(VideoAd.InvalidationReason.CreativeFailed(400), VideoAd.InvalidationReason.CreativeFailed(405))
        assertNotEquals(VideoAd.InvalidationReason.CreativeFailed(400), VideoAd.InvalidationReason.Expired)
        assertEquals(VideoAd.Click.Area.Creative, VideoAd.Click.Area.from(null))
        assertEquals(VideoAd.Click.Area.Creative, VideoAd.Click.Area.from("creative"))
        assertEquals(VideoAd.Click.Area.EndCard, VideoAd.Click.Area.from("endCard"))
        assertEquals(VideoAd.Click.Area.Other("logo"), VideoAd.Click.Area.from("logo"))
        assertNotEquals(VideoAd.Click.Area.Other("logo"), VideoAd.Click.Area.Other("cta"))
        assertNotEquals(VideoAd.Click.Area.Other("creative"), VideoAd.Click.Area.Creative)
        assertEquals("logo".hashCode(), VideoAd.Click.Area.Other("logo").hashCode())
        assertEquals(VideoAd.Click(VideoAd.Click.Area.EndCard), VideoAd.Click(VideoAd.Click.Area.EndCard))
        assertNotEquals(VideoAd.Click(VideoAd.Click.Area.EndCard), VideoAd.Click(VideoAd.Click.Area.Creative))
        assertNotEquals(VideoAd.Click(VideoAd.Click.Area.EndCard), VideoAd.Click.Area.EndCard)
        assertEquals("Click(Other(cta))", VideoAd.Click(VideoAd.Click.Area.Other("cta")).toString())
    }
}
