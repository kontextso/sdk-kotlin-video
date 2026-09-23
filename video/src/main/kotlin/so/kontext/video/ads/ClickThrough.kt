package so.kontext.video.ads

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import so.kontext.video.internal.log

/** Opens a click-through URL in Chrome Custom Tabs, falling back to any app that handles the link. */
internal object ClickThrough {
    fun open(context: Context, url: String) {
        val uri = Uri.parse(url)
        val customTab = CustomTabsIntent.Builder().build()
        if (context !is Activity) customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            customTab.launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            log("no browser for custom tab (${e.message}); falling back to ACTION_VIEW")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.onFailure { log("no app handles $uri: $it") }
        }
    }
}
