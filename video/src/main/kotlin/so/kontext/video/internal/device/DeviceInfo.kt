package so.kontext.video.internal.device

import android.content.Context
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
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
import so.kontext.video.internal.payloads.putIfNotNull
import so.kontext.video.internal.platform.Environment
import kotlin.math.roundToInt

/**
 * The device, app and OS facts every request carries, read through KontextKit, whose values are the contract's:
 * form factors in its OpenRTB-derived set, sizes in dp (the server multiplies by `dpr` where OpenRTB wants
 * physical pixels). As the Swift SDK does.
 */
internal object DeviceInfo {
    /** The contract's `type` values, as KontextKit names them. */
    private val NETWORK_TYPES = setOf("wifi", "cellular", "ethernet", "other")

    /** The longest carrier name the server takes; a longer one would fail the whole request. */
    private const val MAX_CARRIER_LENGTH = 128

    /** How long a request waits for the advertising id. A constant in spirit; tests shorten it. */
    @VisibleForTesting
    var advertisingIdWaitMillis = 1_000L

    /** Play services, through KontextKit; tests replace it. */
    @VisibleForTesting
    var readAdvertisingId: suspend (Context) -> String? = { context ->
        AdvertisingIdProvider.collect(context).takeUnless { it.isLimitAdTrackingEnabled }?.advertisingId
    }

    /** The advertising id read in flight; a finished one is not reused, so each request after it reads afresh. */
    private var advertisingIdRead: Deferred<String?>? = null

    /**
     * The network facts, read through KontextKit on a worker; tests replace it. Only once KontextKit has the
     * WebView's user agent ([UserAgent.isKnown]): its read takes that too, and the first one starts a WebView, which
     * a request must not wait for. Until then a request carries the user agent alone.
     */
    @VisibleForTesting
    var readNetworkFacts: (Context) -> NetworkFacts? = ::networkFactsOnceKnown

    private fun networkFactsOnceKnown(context: Context): NetworkFacts? = if (UserAgent.isKnown) networkFactsNow(context) else null

    /** The `device.network` facts besides the user agent, in the contract's values; each `null` when the device does not say. */
    class NetworkFacts(val type: String?, val generation: String?, val carrier: String?, val mccmnc: String?)

    /** Identifies this installation of the app; kept until the app is uninstalled. */
    fun installId(context: Context): String = synchronized(this) { InstallIdProvider.getOrCreate(context) }

    fun app(context: Context): JsonObject {
        val info = AppInfoProvider.collect(context)
        return buildJsonObject {
            put("bundleId", info.bundleId)
            put("version", info.version)
        }
    }

    /** The `os` object of a diagnostics batch: the name and the version only. */
    fun osForReports(context: Context): JsonObject {
        val info = OSInfoProvider.collect(context)
        return buildJsonObject {
            put("name", info.name)
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

    /** [readNetworkFacts] off the main thread (system services); `null` when it throws or has nothing yet. */
    suspend fun networkFacts(context: Context): NetworkFacts? =
        withContext(Dispatchers.IO) { runCatching { readNetworkFacts(context) }.getOrNull() }

    /** The `device.network` object: the user agent and the network facts, each left out when unknown. */
    fun network(userAgent: String?, facts: NetworkFacts?): JsonObject = buildJsonObject {
        putIfNotNull("userAgent", userAgent)
        putIfNotNull("type", facts?.type)
        putIfNotNull("generation", facts?.generation)
        putIfNotNull("carrier", facts?.carrier)
        putIfNotNull("mccmnc", facts?.mccmnc)
    }

    /**
     * KontextKit's transport, radio and carrier name, and the operator's MCC-MNC, which needs no permission. The
     * carrier and the MCC-MNC only on a cellular connection, as KontextKit gives the carrier: on Wi-Fi they would
     * describe a network the request does not use.
     */
    private fun networkFactsNow(context: Context): NetworkFacts {
        val info = NetworkInfoProvider.collect(context)
        val isCellular = info.type == "cellular"
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return NetworkFacts(
            type = info.type.takeIf { it in NETWORK_TYPES },
            generation = generationOf(info.detail),
            carrier = info.carrier?.takeIf { isCellular && it.length <= MAX_CARRIER_LENGTH },
            mccmnc = if (isCellular) mccmncOf(runCatching { telephony?.networkOperator }.getOrNull()) else null,
        )
    }

    /**
     * KontextKit's cellular detail as the contract's `generation`: `gprs`, `edge` and `2g` are 2G, `3g` and `hspa`
     * 3G, `lte` 4G, `5g` (or `nr`) 5G; anything else, or none, is left out.
     */
    fun generationOf(detail: String?): String? = when (detail) {
        "gprs", "edge", "2g" -> "2g"
        "3g", "hspa" -> "3g"
        "lte" -> "4g"
        "5g", "nr" -> "5g"
        else -> null
    }

    /** The operator's numeric code (`23002`, MCC then MNC) as `MCC-MNC` (`230-02`); `null` unless it is 5 or 6 digits. */
    fun mccmncOf(networkOperator: String?): String? {
        if (networkOperator == null || networkOperator.length !in 5..6 || !networkOperator.all { it in '0'..'9' }) return null
        return "${networkOperator.take(3)}-${networkOperator.drop(3)}"
    }

    /**
     * The advertising id, `null` when the viewer limited ad tracking, Play services cannot tell, or it did not
     * answer within [advertisingIdWaitMillis]: Play services' call blocks and documents no time limit, and a
     * request must not wait for it. The read goes on by itself (a blocked call cannot be stopped); waiting for it
     * stops at once when the caller is cancelled (its `VideoAds` destroyed), never swallowed. Requests made while
     * one read hangs share it: a hung Play services holds one IO thread, not one per request.
     */
    suspend fun advertisingId(context: Context): String? {
        val read = synchronized(this) {
            advertisingIdRead?.takeIf { it.isActive }
                ?: Environment.scope.async(Dispatchers.IO) { runCatching { readAdvertisingId(context) }.getOrNull() }.also { advertisingIdRead = it }
        }
        return withTimeoutOrNull(advertisingIdWaitMillis) { read.await() }
    }

    fun resetForTesting() {
        readNetworkFacts = ::networkFactsOnceKnown
    }
}
