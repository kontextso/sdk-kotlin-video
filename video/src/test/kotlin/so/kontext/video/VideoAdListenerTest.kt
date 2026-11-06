package so.kontext.video

import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The listener's methods all have a default: an app implements only what it needs. */
@RunWith(RobolectricTestRunner::class)
class VideoAdListenerTest : SdkTest() {
    @Test fun aListenerNeedsNoMethods() {
        val listener = object : VideoAdListener {}
        val ad = overlay()
        listener.onAdImpression(ad)
        listener.onAdClicked(ad, VideoAd.Click(VideoAd.Click.Area.Creative))
        listener.onAdFailed(ad, VideoAdException.NotLoaded())
        listener.onAdEnded(ad, VideoAd.EndReason.DISMISSED)
        assertFalse(ad.hasEnded)
    }
}
