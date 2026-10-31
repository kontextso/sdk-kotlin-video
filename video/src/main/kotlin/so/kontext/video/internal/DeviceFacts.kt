package so.kontext.video.internal

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
import kotlin.math.roundToInt
import android.content.res.Configuration as AndroidConfiguration

/**
 * The device, app and OS facts every request carries, read through KontextKit. Sizes go in dp: the server
 * multiplies by `dpr` where OpenRTB wants physical pixels.
 */
internal object DeviceFacts {
    /** The SDK's identity, as the server knows it. */
    const val SDK_NAME = "sdk-kotlin-video"

    fun sdk(): JsonObject = buildJsonObject {
        put("name", SDK_NAME)
        put("version", so.kontext.video.BuildConfig.SDK_VERSION)
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
        val metrics = context.resources.displayMetrics
        val night = context.resources.configuration.uiMode and AndroidConfiguration.UI_MODE_NIGHT_MASK
        return buildJsonObject {
            put("width", (metrics.widthPixels / metrics.density).roundToInt())
            put("height", (metrics.heightPixels / metrics.density).roundToInt())
            put("dpr", metrics.density.toDouble())
            put("darkMode", night == AndroidConfiguration.UI_MODE_NIGHT_YES)
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

    /** The media volume, 0–100; below 1 % counts as muted. */
    fun audio(context: Context): JsonObject {
        val info = AudioInfoProvider.collect(context)
        return buildJsonObject {
            put("volume", info.volume)
            put("muted", info.muted || info.volume < 1)
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

    /** The advertising id, `null` when the viewer limited ad tracking or Play services cannot tell. */
    suspend fun advertisingId(context: Context): String? = runCatching {
        AdvertisingIdProvider.collect(context).takeUnless { it.isLimitAdTrackingEnabled }?.advertisingId
    }.getOrNull()

    fun resetForTesting() {
        userAgentLookup = null
    }
}
