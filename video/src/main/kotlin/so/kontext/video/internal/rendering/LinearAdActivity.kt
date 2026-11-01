package so.kontext.video.internal.rendering

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.UUID

/**
 * The full-screen screen the SDK starts for a linear ad. It owns nothing but the layout: the ad owns the
 * page and decides when it ends; the Activity tells the ad when it is shown, and when the system took it
 * away (`INTERRUPTED`). Back does nothing: the creative's own controls end the ad.
 */
internal class LinearAdActivity : ComponentActivity() {
    /** What a linear ad gives its screen. */
    interface Presentation {
        /** The screen is up: put the page in [root]. */
        fun attach(activity: LinearAdActivity, root: FrameLayout)

        /** The screen is visible to the viewer. */
        fun onScreenShown()

        /** The screen is gone: [byAd] when the ad finished it, else the system or the viewer took it away. */
        fun onScreenGone(byAd: Boolean)
    }

    private var presentation: Presentation? = null
    private var shown = false

    /** Set by the ad while it finishes the screen itself, so `onDestroy` is not an interruption. */
    var isEndingByAd = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val presentation = token?.let { presentations[it] }
        // A screen restored after the process died, or started twice: there is no ad for it.
        if (presentation == null || savedInstanceState != null) {
            finish()
            return
        }
        this.presentation = presentation
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )
        presentation.attach(this, root)
    }

    override fun onResume() {
        super.onResume()
        if (!shown) {
            shown = true
            presentation?.onScreenShown()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val presentation = presentation ?: return
        intent.getStringExtra(EXTRA_TOKEN)?.let { presentations.remove(it) }
        // The manifest's configChanges lists every change it can declare, so the page lives through rotation,
        // resizing, a new font scale, locale or density. A screen recreated anyway (a change that cannot be
        // declared) has lost its page, as has one the system took away: the ad ends now, interrupted.
        presentation.onScreenGone(isEndingByAd)
    }

    companion object {
        private const val EXTRA_TOKEN = "so.kontext.video.token"
        private val presentations = mutableMapOf<String, Presentation>()

        /** Starts the screen for [presentation] from [from]; the token finds it again in `onCreate`. */
        fun start(from: Activity, presentation: Presentation) {
            val token = UUID.randomUUID().toString()
            presentations[token] = presentation
            // The app started the ad break, the viewer did not leave: without the flag the host hears
            // onUserLeaveHint(), where apps enter picture-in-picture or note that the user left.
            val intent = Intent(from, LinearAdActivity::class.java).putExtra(EXTRA_TOKEN, token).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            from.startActivity(intent)
            @Suppress("DEPRECATION")
            from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        /** The presentation never reached its screen: its token goes. */
        fun forget(presentation: Presentation) {
            presentations.entries.removeAll { it.value === presentation }
        }
    }
}
