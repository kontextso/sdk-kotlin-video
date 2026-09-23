package so.kontext.video.ads.interstitial

import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * The full-screen host the SDK starts for an interstitial. Nothing but a
 * black container: the ad view is mounted into it by the placement. Back is
 * disabled, so the only ways out are the ad's own Close chip or the
 * placement's `hide()`.
 */
public class InterstitialAdActivity : ComponentActivity() {
    private var placement: InterstitialAd? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = Unit
            },
        )
        val placement = intent.getStringExtra(EXTRA_TOKEN)?.let { placements[it] }
        if (placement == null) {
            finish()
            return
        }
        this.placement = placement
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        placement.attach(this, root)
    }

    override fun onDestroy() {
        super.onDestroy()
        placement?.onScreenGone(this)
        placement = null
    }

    internal companion object {
        const val EXTRA_TOKEN: String = "so.kontext.video.interstitial.token"

        private val placements = mutableMapOf<String, InterstitialAd>()

        fun register(token: String, placement: InterstitialAd) {
            placements[token] = placement
        }

        fun unregister(token: String) {
            placements.remove(token)
        }
    }
}
