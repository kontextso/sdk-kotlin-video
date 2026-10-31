package so.kontext.video.publisher

import android.app.Application
import so.kontext.video.Configuration
import so.kontext.video.KontextVideo

class PublisherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The ad server's demo routes answer any token.
        KontextVideo.initialize(this, Configuration("publisher-app-check"))
    }
}
