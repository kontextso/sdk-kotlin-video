package so.kontext.video.internal.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import so.kontext.video.internal.diagnostics.Logger

/**
 * Which links an ad may open, and opening them: the click-through of an `ad.click`, the target of an `ad.open`
 * (the AdChoices page), a navigation the page could not report.
 *
 * Web, store and the advertiser's app links open. Refused: `intent:`, which could start any Android component;
 * the schemes that call, text or pay without a page in between; and the rest, which are not links to anywhere.
 * Links into the host app open too: which links an ad may carry is the ad server's decision.
 */
internal object LinkOpener {
    private val refusedSchemes = setOf(
        "intent", "javascript", "data", "file", "blob", "about", "content",
        "tel", "sms", "smsto", "mms", "mmsto",
    )

    /** A URL's scheme, at its start. */
    private val schemePattern = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /**
     * A link that may open: a usable URL (a scheme, no spaces or control characters, which iOS's URL parsing
     * refuses too, and a host for a web link) whose scheme is not refused.
     */
    fun isAllowed(url: String): Boolean {
        val scheme = schemePattern.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        if (url.any { it <= ' ' || it == '\u007F' }) return false
        if ((scheme == "http" || scheme == "https") && Uri.parse(url).host.isNullOrEmpty()) return false
        return scheme !in refusedSchemes
    }

    /** Opens [url], else [fallback] when nothing opens the first. Says whether one of them opened. */
    fun open(context: Context, url: String, fallback: String?): Boolean {
        if (startView(context, url)) return true
        return fallback != null && startView(context, fallback)
    }

    /**
     * Opens [url] as a browser opens a link: a BROWSABLE intent with no component or selector, so only what
     * declares itself reachable from the web starts (the advertiser's app links do, and the host app's own:
     * which links an ad may carry is the ad server's decision). An app the system cannot see or that is
     * absent surfaces as `ActivityNotFoundException`, and the fallback opens.
     */
    private fun startView(context: Context, url: String): Boolean {
        if (!isAllowed(url)) {
            Logger.warning { "the link $url is not allowed to open" }
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
}
