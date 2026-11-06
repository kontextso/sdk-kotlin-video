package so.kontext.video.internal.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.screen.InAppBrowser

/**
 * Which links an ad may open, and opening them: the click-through of an `ad.click`, the target of an `ad.open`
 * (the AdChoices page), a navigation the page could not report.
 *
 * Web, store and the advertiser's app links open. Refused: `intent:`, which could start any Android component;
 * the schemes that call, text or pay without a page in between; and the rest, which are not links to anywhere.
 * Links into the host app open too: which links an ad may carry is the ad server's decision.
 *
 * Each target goes where its [LinkRoute] says, and the fallback the same way when the first opened nothing:
 * ```
 * refused        nothing opens
 * APP, STORE     ACTION_VIEW: the app it names, the Play Store
 * WEB            the app that claims it (openInClaimingApp), else the ad's in-app browser (a Custom Tab), else
 *                ACTION_VIEW: the default browser (no Custom Tabs browser, or no Activity to open it over)
 * ```
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

    /**
     * Opens [url], else [fallback] when nothing opens the first; a web link in [browser] when no app claims it.
     * Says whether one of them opened.
     */
    fun open(context: Context, url: String, fallback: String?, browser: InAppBrowser? = null): Boolean {
        if (openOne(context, url, browser)) return true
        return fallback != null && openOne(context, fallback, browser)
    }

    private fun openOne(context: Context, url: String, browser: InAppBrowser?): Boolean {
        if (!isAllowed(url)) {
            Logger.warning { "the link $url is not allowed to open" }
            return false
        }
        val uri = Uri.parse(url)
        return when (LinkRoute.of(uri.scheme, uri.host, uri.path)) {
            LinkRoute.APP, LinkRoute.STORE -> startView(context, viewIntent(uri))
            LinkRoute.WEB -> openInClaimingApp(context, uri) || browser?.open(url) == true || startView(context, viewIntent(uri))
        }
    }

    /**
     * Opens a web link in the installed app that claims it, if one does; `false` when only browsers would open it.
     *
     * Android 11 and later: the system decides, with `FLAG_ACTIVITY_REQUIRE_NON_BROWSER` (the start throws when
     * a browser would take the link). Android 8 to 10 have no such flag: the apps that open this link, less those
     * that open any web link (the browsers), are the ones that claim it; one of them opens it, else nothing does
     * here (those versions cannot tell a verified claim from another). They let the SDK see every app, so the
     * comparison needs no `<queries>`. From Android 12 a link no app verified, or one the viewer set to open in a
     * browser, opens nowhere here and goes on to the in-app browser.
     */
    private fun openInClaimingApp(context: Context, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return startView(context, viewIntent(uri).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))
        }
        val packageManager = context.packageManager
        val browsers = Intent(Intent.ACTION_VIEW, Uri.fromParts(uri.scheme, "", null)).addCategory(Intent.CATEGORY_BROWSABLE)
        val browserPackages = packageManager.queryIntentActivities(browsers, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
        val claiming = packageManager.queryIntentActivities(viewIntent(uri), PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct() - browserPackages
        // One app: straight to it, as Android 11 opens a verified link. Several: the system's chooser among them.
        val intent = viewIntent(uri)
        when (claiming.size) {
            0 -> return false
            1 -> intent.setPackage(claiming.single())
        }
        return startView(context, intent)
    }

    /**
     * A link opened as a browser opens it: a BROWSABLE intent with no component or selector, so only what
     * declares itself reachable from the web starts (the advertiser's app links do, and the host app's own:
     * which links an ad may carry is the ad server's decision).
     */
    private fun viewIntent(uri: Uri): Intent {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.component = null
        intent.selector = null
        return intent
    }

    /** Starts [intent]. An app the system cannot see or that is absent surfaces as `ActivityNotFoundException`: `false`. */
    private fun startView(context: Context, intent: Intent): Boolean {
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
