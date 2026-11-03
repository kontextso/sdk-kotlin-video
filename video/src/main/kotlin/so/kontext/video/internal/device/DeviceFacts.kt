package so.kontext.video.internal.device

import android.content.Context
import android.os.Build
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import so.kontext.kit.deviceinfo.AdvertisingIdProvider
import so.kontext.kit.deviceinfo.AppInfoProvider
import so.kontext.kit.deviceinfo.AudioInfoProvider
import so.kontext.kit.deviceinfo.BatteryInfoProvider
import so.kontext.kit.deviceinfo.HardwareInfoProvider
import so.kontext.kit.deviceinfo.InstallIdProvider
import so.kontext.kit.deviceinfo.NetworkInfoProvider
import so.kontext.kit.deviceinfo.OSInfoProvider
import so.kontext.kit.deviceinfo.ScreenInfoProvider
import so.kontext.video.internal.network.putIfNotNull
import so.kontext.video.internal.support.Environment
import so.kontext.video.internal.support.Logger
import kotlin.math.roundToInt

/**
 * The device, app and OS facts every request carries, read through KontextKit, whose values are the contract's:
 * form factors in its OpenRTB-derived set, sizes in dp (the server multiplies by `dpr` where OpenRTB wants
 * physical pixels). As the Swift SDK does.
 */
internal object DeviceFacts {
    /** The SDK's identity, as the server knows it. */
    const val SDK_NAME = "sdk-kotlin-video"

    fun sdk(): JsonObject = buildJsonObject {
        put("name", SDK_NAME)
        put("version", so.kontext.video.internal.SDK_VERSION)
    }

    /** Identifies this installation of the app; kept until the app is uninstalled. */
    fun installId(context: Context): String = InstallIdProvider.getOrCreate(context)

    fun app(context: Context): JsonObject {
        val info = AppInfoProvider.collect(context)
        return buildJsonObject {
            put("bundleId", info.bundleId)
            put("version", info.version)
        }
    }

    fun os(context: Context): JsonObject {
        val info = OSInfoProvider.collect(context)
        return buildJsonObject {
            put("name", info.name)
            put("version", info.version)
            put("locale", info.locale)
            put("timezone", info.timezone)
        }
    }

    fun hardware(context: Context): JsonObject {
        val info = HardwareInfoProvider.collect(context)
        return buildJsonObject {
            put("brand", info.brand)
            put("model", info.model)
            put("type", info.type)
        }
    }

    /** The screen in dp, its density, and the system's dark mode. */
    fun screen(context: Context): JsonObject {
        val info = ScreenInfoProvider.collect(context)
        return buildJsonObject {
            put("width", info.width)
            put("height", info.height)
            put("dpr", info.dpr)
            put("darkMode", info.darkMode)
        }
    }

    fun power(context: Context): JsonObject {
        val info = BatteryInfoProvider.collect(context)
        return buildJsonObject {
            putIfNotNull("batteryLevel", info.batteryLevel?.roundToInt())
            put("batteryState", info.batteryState)
            put("lowPowerMode", info.lowPowerMode)
        }
    }

    /** The media volume, 0–100, and whether it is muted (KontextKit counts 0 % as muted). */
    fun audio(context: Context): JsonObject {
        val info = AudioInfoProvider.collect(context)
        return buildJsonObject {
            put("volume", info.volume)
            put("muted", info.muted)
        }
    }

    /** The one user-agent lookup in flight or done: KontextKit makes a WebView until it has cached the answer. */
    private var userAgentLookup: Deferred<String?>? = null

    /** `navigator.userAgent` of a WebView; `null` when WebKit could not answer, and the next caller tries again. */
    suspend fun userAgent(context: Context): String? {
        val lookup = userAgentLookup ?: Environment.scope.lookUpUserAgent(context).also { userAgentLookup = it }
        val agent = lookup.await()
        if (agent == null && userAgentLookup === lookup) userAgentLookup = null
        return agent
    }

    private fun CoroutineScope.lookUpUserAgent(context: Context): Deferred<String?> =
        async { runCatching { NetworkInfoProvider.collect(context).userAgent }.getOrNull() }

    /**
     * The user agent an ads request carries: the WebView's ([Environment.userAgent]), waited for at most
     * [Environment.userAgentWaitMillis]. On a cold device the WebView's first answer can take many seconds, and
     * the request's own timeout starts only once it is built: past the wait, the request goes with
     * [fallbackUserAgent] and the lookup goes on for the requests after it (waiting gives up, it does not
     * cancel the shared read). A lookup that found none in time sends none, as before; a cancelled caller (its
     * `VideoAds` destroyed) stops at once. As the Swift SDK does.
     */
    suspend fun requestUserAgent(context: Context): String? {
        val wait = Environment.userAgentWaitMillis
        val found = withTimeoutOrNull(wait) { Found(Environment.userAgent()) }
        if (found != null) return found.agent
        Logger.debug { "user agent not ready within $wait ms; this request carries the fallback" }
        return fallbackUserAgent(context)
    }

    private class Found(val agent: String?)

    /**
     * The shape of an Android WebView's user agent, from the OS version, the device and the installed WebView,
     * for a request that cannot wait for the real one. A tablet's has no `Mobile`, as the WebView's has not.
     */
    fun fallbackUserAgent(context: Context): String {
        val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull()
        val chrome = webView?.substringBefore(' ')?.takeIf { it.isNotEmpty() }?.let { " Chrome/$it" } ?: ""
        val mobile = if (context.resources.configuration.smallestScreenWidthDp >= TABLET_WIDTH_DP) "" else " Mobile"
        return "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL} Build/${Build.ID}; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0$chrome$mobile Safari/537.36"
    }

    private const val TABLET_WIDTH_DP = 600

    /** The advertising id, `null` when the viewer limited ad tracking or Play services cannot tell. */
    suspend fun advertisingId(context: Context): String? = runCatching {
        AdvertisingIdProvider.collect(context).takeUnless { it.isLimitAdTrackingEnabled }?.advertisingId
    }.getOrNull()

    fun resetForTesting() {
        userAgentLookup = null
    }
}
