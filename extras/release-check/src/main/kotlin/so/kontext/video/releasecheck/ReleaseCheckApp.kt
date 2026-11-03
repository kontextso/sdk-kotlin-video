package so.kontext.video.releasecheck

import android.app.Application
import so.kontext.video.KontextVideo.Configuration
import so.kontext.video.KontextVideo

class ReleaseCheckApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The ad server's demo routes answer any token.
        KontextVideo.initialize(this, Configuration("release-check"))
    }
}
