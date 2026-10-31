package so.kontext.video.example

import android.app.Application
import so.kontext.video.Configuration
import so.kontext.video.KontextVideo

class ExampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The ad server's demo routes answer any token; put your own publisher token here.
        KontextVideo.initialize(this, Configuration("example-debug") { logLevel = Configuration.LogLevel.DEBUG })
    }
}
