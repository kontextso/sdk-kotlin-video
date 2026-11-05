package so.kontext.video.internal.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View

/** The Activity this context belongs to, through any context wrappers; `null` for one of no Activity. */
internal val Context.activity: Activity?
    get() {
        var context: Context? = this
        while (context is ContextWrapper) {
            if (context is Activity) return context
            context = context.baseContext
        }
        return null
    }

/** The Activity the view is in, through any context wrappers. */
internal val View.activity: Activity? get() = context.activity
