package so.kontext.video.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import so.kontext.video.Constants
import so.kontext.video.model.VideoAdTracking
import so.kontext.video.network.HttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Fires the ad server's tracking URLs for one served ad. `[CACHEBUSTING]`
 * becomes a fresh random number per fire, `[TIMESTAMP]` the current ISO-8601
 * time, any other `[MACRO]` becomes empty, and every URL fires at most once
 * (mute/unmute may repeat). Pings are `POST`s whose body repeats the query's
 * `e=` / `event=` as `{event}` so the server can read either.
 */
internal class AdTracker(private val tracking: VideoAdTracking) {
    enum class Event { IMPRESSION, CREATIVE_VIEW, VIEWABLE, NOT_VIEWABLE, VIEW_UNDETERMINED, CLOSE, OVERLAY_VIEW_DURATION, CLICK_TRACKING }

    enum class VideoEvent { START, FIRST_QUARTILE, MIDPOINT, THIRD_QUARTILE, COMPLETE, SKIP, MUTE, UNMUTE }

    private val fired = mutableSetOf<Event>()
    private val firedVideo = mutableSetOf<VideoEvent>()
    private var viewabilityDecided = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun fire(event: Event) {
        if (!fired.add(event)) return
        if (event == Event.VIEWABLE || event == Event.NOT_VIEWABLE || event == Event.VIEW_UNDETERMINED) {
            if (viewabilityDecided) return
            viewabilityDecided = true
        }
        send(rawUrl(event))
    }

    fun fireVideo(event: VideoEvent) {
        val video = tracking.video ?: return
        if (event != VideoEvent.MUTE && event != VideoEvent.UNMUTE && !firedVideo.add(event)) return
        val raw = when (event) {
            VideoEvent.START -> video.start
            VideoEvent.FIRST_QUARTILE -> video.firstQuartile
            VideoEvent.MIDPOINT -> video.midpoint
            VideoEvent.THIRD_QUARTILE -> video.thirdQuartile
            VideoEvent.COMPLETE -> video.complete
            VideoEvent.SKIP -> video.skip
            VideoEvent.MUTE -> video.mute
            VideoEvent.UNMUTE -> video.unmute
        }
        raw?.let { send(it) }
    }

    private fun send(raw: String) {
        val url = substituteMacros(raw)
        val event = eventName(url)
        val body = if (event != null) "{\"event\":\"$event\"}" else "{}"
        scope.launch {
            runCatching { HttpClient.postJson(url, body, Constants.TRACKING_TIMEOUT_MS) }
        }
    }

    private fun rawUrl(event: Event): String = when (event) {
        Event.IMPRESSION -> tracking.impression
        Event.CREATIVE_VIEW -> tracking.creativeView
        Event.VIEWABLE -> tracking.viewable
        Event.NOT_VIEWABLE -> tracking.notViewable
        Event.VIEW_UNDETERMINED -> tracking.viewUndetermined
        Event.CLOSE -> tracking.close
        Event.OVERLAY_VIEW_DURATION -> tracking.overlayViewDuration
        Event.CLICK_TRACKING -> tracking.clickTracking
    }

    companion object {
        fun substituteMacros(url: String): String {
            val result = StringBuilder()
            var rest = url
            while (true) {
                val open = rest.indexOf('[')
                if (open < 0) break
                val close = rest.indexOf(']', open)
                if (close < 0) break
                result.append(rest, 0, open)
                when (rest.substring(open + 1, close)) {
                    "CACHEBUSTING" -> result.append(Random.nextInt(10_000_000, 100_000_000))
                    "TIMESTAMP" -> result.append(java.net.URLEncoder.encode(iso8601Now(), "UTF-8"))
                }
                rest = rest.substring(close + 1)
            }
            result.append(rest)
            return result.toString()
        }

        /** The `e=` or `event=` query value, if any. */
        fun eventName(url: String): String? {
            val query = url.substringAfter('?', "").substringBefore('#')
            if (query.isEmpty()) return null
            return query.split('&')
                .map { it.substringBefore('=') to it.substringAfter('=', "") }
                .firstOrNull { it.first == "e" || it.first == "event" }
                ?.second
        }

        private fun iso8601Now(): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date())
        }
    }
}
