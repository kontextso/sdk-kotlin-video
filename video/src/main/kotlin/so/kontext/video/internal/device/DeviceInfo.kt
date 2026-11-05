package so.kontext.video.internal.device

import android.content.Context
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
import so.kontext.video.internal.network.putIfNotNull
import kotlin.math.roundToInt

/**
 * The device, app and OS facts every request carries, read through KontextKit, whose values are the contract's:
 * form factors in its OpenRTB-derived set, sizes in dp (the server multiplies by `dpr` where OpenRTB wants
 * physical pixels). As the Swift SDK does.
 */
internal object DeviceInfo {
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

    /** The advertising id, `null` when the viewer limited ad tracking or Play services cannot tell. */
    suspend fun advertisingId(context: Context): String? = runCatching {
        AdvertisingIdProvider.collect(context).takeUnless { it.isLimitAdTrackingEnabled }?.advertisingId
    }.getOrNull()
}
