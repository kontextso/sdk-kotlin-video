package so.kontext.video.internal.device

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
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
import so.kontext.kit.deviceinfo.OSInfoProvider
import so.kontext.kit.deviceinfo.ScreenInfoProvider
import so.kontext.video.internal.payloads.putIfNotNull
import so.kontext.video.internal.platform.Environment
import kotlin.math.roundToInt

/**
 * The device, app and OS facts every request carries, read through KontextKit, whose values are the contract's:
 * form factors in its OpenRTB-derived set, sizes in dp (the server multiplies by `dpr` where OpenRTB wants
 * physical pixels). As the Swift SDK does.
 */
internal object DeviceInfo {
    /** How long a request waits for the advertising id. A constant in spirit; tests shorten it. */
    @VisibleForTesting
    var advertisingIdWaitMillis = 1_000L

    /** Play services, through KontextKit; tests replace it. */
    @VisibleForTesting
    var readAdvertisingId: suspend (Context) -> String? = { context ->
        AdvertisingIdProvider.collect(context).takeUnless { it.isLimitAdTrackingEnabled }?.advertisingId
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

    /**
     * The advertising id, `null` when the viewer limited ad tracking, Play services cannot tell, or it did not
     * answer within [advertisingIdWaitMillis]: Play services' call blocks and documents no time limit, and a
     * request must not wait for it. The read goes on by itself (a blocked call cannot be stopped); waiting for it
     * stops at once when the caller is cancelled (its `VideoAds` destroyed), never swallowed.
     */
    suspend fun advertisingId(context: Context): String? {
        val read = Environment.scope.async(Dispatchers.IO) { runCatching { readAdvertisingId(context) }.getOrNull() }
        return withTimeoutOrNull(advertisingIdWaitMillis) { read.await() }
    }
}
