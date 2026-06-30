package so.kontext.video.example

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Tells the Google Cast framework which receiver app to launch on the TV.
 *
 * Discovered automatically via the `OPTIONS_PROVIDER_CLASS_NAME` meta-data in
 * the manifest — the framework instantiates it the first time
 * `CastContext.getSharedInstance` runs. We use Google's **Default Media
 * Receiver** (`CC1AD845`), which plays plain HLS/DASH/MP4 URLs with no receiver
 * app to register. (A custom receiver would be needed for DRM-over-Cast or
 * branded UI — out of scope for this test player.)
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
