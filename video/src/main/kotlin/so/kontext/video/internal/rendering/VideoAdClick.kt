package so.kontext.video.internal.rendering

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.Click
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger

// Where a click goes, as Swift's `VideoAd+Click`: the click beacon, the click-through, then the listener.

/** The page's `ad.click`: the `click` beacon, the click-through with the same fire id, then `onAdClicked`. */
internal fun VideoAd.click(url: String, fallback: String?, area: Click.Area) {
    Logger.info { "ad ${id.take(8)}: click ($area) $url" }
    // Every accepted click is counted: an end-card click on an ad without end-card entries (an overlay, a
    // pause ad) fires the main creative's.
    // One occurrence: the beacon and the click-through carry the same fire id.
    val endCard = area == Click.Area.EndCard && tracker.has("click", "endCard")
    val fireId = tracker.fire("click", part = if (endCard) "endCard" else null)
    open(tracker.clickThrough(url, fireId), fallback?.let { tracker.clickThrough(it, fireId) })
    if (hasEnded) return
    listener?.onAdClicked(this, Click(area))
}

/** Opens a URL the page asked for (a click-through, or `ad.open`): the URL, else its fallback. */
internal fun VideoAd.open(url: String, fallback: String?) {
    val context = Environment.context ?: return
    if (startView(context, url)) return
    if (fallback != null && startView(context, fallback)) return
    Logger.warning { "ad ${id.take(8)}: nothing could open the click-through $url" }
}

/**
 * Opens [url] as a browser opens a link: a BROWSABLE intent with no component or selector, so only what
 * declares itself reachable from the web starts (the advertiser's app links do, and the host app's own:
 * which links an ad may carry is the ad server's decision). An app the system cannot see or that is
 * absent surfaces as `ActivityNotFoundException`, and the fallback opens.
 */
private fun VideoAd.startView(context: Context, url: String): Boolean {
    if (!FrameBridge.isAllowed(url)) {
        Logger.warning { "ad ${id.take(8)}: the click-through $url is not a link that may open" }
        return false
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.component = null
    intent.selector = null
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
