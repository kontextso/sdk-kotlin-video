package so.kontext.video.internal.screen

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import so.kontext.video.internal.diagnostics.Logger
import java.util.UUID

/**
 * The full-screen Activity the SDK starts for a linear ad. It owns nothing but the layout: the ad owns the
 * page and decides when it ends; the Activity tells the ad when it is shown, and when the system took it
 * away (`INTERRUPTED`). Back does nothing: the creative's own controls end the ad.
 */
internal class LinearAdActivity : ComponentActivity() {
    /** What a linear ad gives its screen: `LinearScreen` implements it. */
    interface Callbacks {
        /** The screen is up: put the page in [root]. */
        fun attach(activity: LinearAdActivity, root: FrameLayout)

        /** The screen is visible to the viewer. */
        fun onScreenShown()

        /** The screen is gone: [byAd] when the ad finished it, else the system or the viewer took it away. */
        fun onScreenGone(byAd: Boolean)
    }

    private var callbacks: Callbacks? = null
    private var wasShown = false

    /** Set by the ad while it finishes the screen itself, so `onDestroy` is not an interruption. */
    var isEndingByAd = false

    /**
     * Another Activity the viewer cannot see the ad through is over this one: this one is paused (another
     * Activity on top, translucent or a sheet like a store's in-app overlay) while it still draws in its
     * window. A dialog over it does not pause it, and the viewer sees the ad around it: not covered. Nor is
     * an Activity paused beside another app's in split screen (before Android 10 only the focused one is
     * resumed). A full cover stops this Activity and hides its window, which the meter sees on its own.
     */
    val isCovered: Boolean
        get() = !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !isInMultiWindowMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 7 ignores `screenOrientation="behind"` for this screen: a player fixed in landscape would get its ad
        // in portrait. There the ad asks for the player's own fixed orientation; from Android 8 `behind` does it.
        val starterOrientation = intent.getIntExtra(EXTRA_ORIENTATION, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && starterOrientation !in unfixedOrientations) {
            requestedOrientation = starterOrientation
        }
        val token = intent.getStringExtra(EXTRA_TOKEN)
        val callbacks = token?.let { callbacksByToken[it] }
        // 1. Find the ad this screen was started for; none (restored after the process died, or started twice): close.
        if (callbacks == null || savedInstanceState != null) {
            finish()
            return
        }
        this.callbacks = callbacks
        // 2. Full screen: draw behind the bars and hide them (a swipe shows them briefly).
        hideSystemBars()
        // 3. A black root for the page.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // 4. Back does nothing: the creative's controls end the ad.
        ignoreBack()
        // 5. Hand the root to the ad.
        callbacks.attach(this, root)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun ignoreBack() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )
    }

    override fun onResume() {
        super.onResume()
        if (!wasShown) {
            wasShown = true
            callbacks?.onScreenShown()
        }
    }

    /**
     * A finishing Activity is gone for the viewer once it stops: the system may hold its onDestroy back for
     * seconds (until the Activity under it is idle), and the app hears `onAdEnded` only when this one is gone.
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing) gone()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The manifest's configChanges lists every change it can declare, so the page lives through rotation,
        // resizing, a new font scale, locale or density. An Activity recreated anyway (a change that cannot be
        // declared) has lost its page, as has one the system took away: the ad ends now, interrupted.
        gone()
    }

    /** Tells the ad once that its screen is gone: at the stop of a finishing screen, else at its destroy. */
    private fun gone() {
        val callbacks = callbacks ?: return
        this.callbacks = null
        intent.getStringExtra(EXTRA_TOKEN)?.let { callbacksByToken.remove(it) }
        callbacks.onScreenGone(isEndingByAd)
    }

    companion object {
        private const val EXTRA_TOKEN = "so.kontext.video.token"
        private const val EXTRA_ORIENTATION = "so.kontext.video.orientation"

        /** Orientations that fix nothing: the screen keeps `behind`. */
        private val unfixedOrientations = setOf(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            ActivityInfo.SCREEN_ORIENTATION_BEHIND,
            ActivityInfo.SCREEN_ORIENTATION_USER,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR,
            ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            ActivityInfo.SCREEN_ORIENTATION_NOSENSOR,
        )
        private val callbacksByToken = mutableMapOf<String, Callbacks>()

        /** Starts the screen for [callbacks] from [from]; the token finds them again in `onCreate`. */
        fun start(from: Activity, callbacks: Callbacks) {
            // Android adds no other Activity to a singleInstance task: the ad opens in another task of the app,
            // and when it ends the viewer lands there, not back on the player.
            if (isSingleInstance(from)) {
                Logger.warning {
                    "the linear ad starts from ${from.javaClass.simpleName}, a singleInstance Activity: it opens in another task, " +
                        "and the viewer returns there, not to the player, when it ends"
                }
            }
            val token = UUID.randomUUID().toString()
            callbacksByToken[token] = callbacks
            // The app started the ad break, the viewer did not leave: without the flag the host hears
            // onUserLeaveHint(), where apps enter picture-in-picture or note that the user left.
            val intent = Intent(from, LinearAdActivity::class.java).putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_ORIENTATION, from.requestedOrientation)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            from.startActivity(intent)
            @Suppress("DEPRECATION")
            from.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }

        private fun isSingleInstance(activity: Activity): Boolean = runCatching {
            activity.packageManager.getActivityInfo(activity.componentName, 0).launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
        }.getOrDefault(false)

        /** The ad never reached its screen: its token goes. */
        fun forget(callbacks: Callbacks) {
            callbacksByToken.entries.removeAll { it.value === callbacks }
        }
    }
}
