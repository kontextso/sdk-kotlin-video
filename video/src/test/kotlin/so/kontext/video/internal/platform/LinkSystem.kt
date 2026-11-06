package so.kontext.video.internal.platform

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import org.robolectric.Shadows.shadowOf
import so.kontext.video.internal.screen.InAppBrowser

/**
 * The system's side of opening a link, for the tests: the apps installed (browsers, apps that claim links, a
 * Custom Tabs browser) and an application context whose starts resolve as Android resolves them.
 *
 * Robolectric starts any intent it is given, and knows no `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`: this context throws
 * `ActivityNotFoundException` when nothing installed opens the intent, and, with that flag, when only browsers
 * would. What it started is in [started]. Starts from an Activity (the Custom Tab) stay Robolectric's.
 */
class LinkSystem(private val app: Application) : ContextWrapper(app) {
    /** The intents this context started, in order. */
    val started = mutableListOf<Intent>()

    // The SDK keeps the application context: this stands in as one.
    override fun getApplicationContext() = this

    override fun startActivity(intent: Intent) {
        val handlers = app.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .filter { intent.`package` == null || it == intent.`package` }
        val browsers = browserPackages()
        val reachable = if (intent.flags and Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER != 0) handlers - browsers else handlers
        if (reachable.isEmpty()) throw ActivityNotFoundException("nothing opens ${intent.dataString}")
        started += intent
    }

    /** A browser: it opens every `http` and `https` link; [customTabs] when it also offers Custom Tabs. */
    fun browser(packageName: String = "com.browser.test", customTabs: Boolean = false) {
        activity(packageName, "Browser") {
            addDataScheme("http")
            addDataScheme("https")
        }
        if (customTabs) {
            val service = ComponentName(packageName, "$packageName.CustomTabsService")
            val packageManager = shadowOf(app.packageManager)
            packageManager.addServiceIfNotPresent(service)
            packageManager.addIntentFilterForService(service, IntentFilter(InAppBrowser.CUSTOM_TABS_SERVICE))
        }
    }

    /** An app that claims the web links of [host], as a verified App Link declares it. */
    fun appLink(packageName: String, host: String) = activity(packageName, "LinkActivity") {
        addDataScheme("https")
        addDataAuthority(host, null)
    }

    /** An app that opens [scheme] links: a custom scheme, or the store's. */
    fun scheme(packageName: String, scheme: String) = activity(packageName, "SchemeActivity") { addDataScheme(scheme) }

    private fun activity(packageName: String, name: String, data: IntentFilter.() -> Unit) {
        val component = ComponentName(packageName, "$packageName.$name")
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                data()
            },
        )
    }

    private fun browserPackages(): Set<String> {
        val any = Intent(Intent.ACTION_VIEW, Uri.fromParts("https", "", null)).addCategory(Intent.CATEGORY_BROWSABLE)
        return app.packageManager.queryIntentActivities(any, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
    }
}
