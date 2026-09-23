package so.kontext.video.ads

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Shared one-shot image loader for ad views. `onLoaded` runs on the main
 * thread once the image is set — that is the moment the impression fires.
 * `onFailed` runs when the URL is bad or the download/decode fails, so the
 * caller can surface it instead of leaving a blank box on screen.
 */
internal object AdImageLoader {
    fun load(
        url: String,
        into: ImageView,
        scope: CoroutineScope,
        onLoaded: () -> Unit,
        onFailed: (String) -> Unit,
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { download(url) } }
            result.onSuccess { bitmap ->
                if (bitmap == null) {
                    onFailed("image data is not a decodable image")
                } else {
                    into.setImageBitmap(bitmap)
                    onLoaded()
                }
            }.onFailure { onFailed("image download failed: ${it.message}") }
        }
    }

    private fun download(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("image http ${connection.responseCode}")
            return connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }

    private const val TIMEOUT_MS = 10_000
}
