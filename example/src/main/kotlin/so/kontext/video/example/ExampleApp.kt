package so.kontext.video.example

import android.app.Application
import so.kontext.video.KontextVideo
import so.kontext.video.KontextVideo.Configuration
import java.util.UUID

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Your publisher token, from Kontext: `./gradlew :example:installDebug -PkontextPublisherToken=…`.
        KontextVideo.initialize(this, Configuration(BuildConfig.PUBLISHER_TOKEN) { logLevel = Configuration.LogLevel.DEBUG })
        // Every ads request names the viewer. This app has no accounts, so each install is one guest, with an id
        // of its own kept for the install; an app with accounts sets the account's id once the viewer logs in.
        KontextVideo.user = KontextVideo.User(guestId())
    }

    private fun guestId(): String {
        val preferences = getSharedPreferences("example", MODE_PRIVATE)
        return preferences.getString("guestId", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("guestId", it).apply()
        }
    }
}
