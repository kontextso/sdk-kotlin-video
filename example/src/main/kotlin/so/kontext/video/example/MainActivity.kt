package so.kontext.video.example

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/** The home screen: one tile per video. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(16))
            setBackgroundColor(Color.BLACK)
        }
        list.addView(TextView(this).apply {
            text = "Kontext Video"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setPadding(0, 0, 0, dp(16))
        })
        for (video in Video.all) {
            list.addView(TextView(this).apply {
                text = "${video.title}\n${video.caption}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setBackgroundColor(video.color)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(28), dp(16), dp(28))
                setOnClickListener { startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_VIDEO, video.id)) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(12)
                }
            })
        }
        setContentView(ScrollView(this).apply { addView(list) })
        // `adb shell am start -n so.kontext.video.example/.MainActivity --es video demo-overlay` opens a video directly.
        intent.getStringExtra(PlayerActivity.EXTRA_VIDEO)?.let { id ->
            startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_VIDEO, id))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
