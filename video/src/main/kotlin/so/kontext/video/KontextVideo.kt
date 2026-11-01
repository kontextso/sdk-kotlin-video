package so.kontext.video

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import so.kontext.video.internal.Sdk

/**
 * The SDK's one entry point. Call [initialize] once, in `Application.onCreate`; then create a [VideoAds]
 * per episode. Every call is main-thread.
 */
public object KontextVideo {
    /** The SDK version. */
    public const val VERSION: String = BuildConfig.SDK_VERSION

    /** Where the SDK is: what `/config` said, or that it was not asked yet. */
    public sealed class State {
        /** [initialize] was not called. */
        public object NotInitialized : State() {
            override fun toString(): String = "NotInitialized"
        }

        /** `/config` is in flight. */
        public object Initializing : State() {
            override fun toString(): String = "Initializing"
        }

        /** Ads can be requested. */
        public object Enabled : State() {
            override fun toString(): String = "Enabled"
        }

        /** The server switched the SDK off for this launch. */
        public class Disabled(public val reason: String) : State() {
            override fun equals(other: Any?): Boolean = other is Disabled && reason == other.reason

            override fun hashCode(): Int = reason.hashCode()

            override fun toString(): String = "Disabled($reason)"
        }

        /** `/config` failed; the next `format()` tries it again. */
        public object Failed : State() {
            override fun toString(): String = "Failed"
        }
    }

    /** Told once `/config` has answered or failed, on the main thread. */
    public fun interface InitializationListener {
        public fun onInitialized(state: State)
    }

    /** The current state. */
    @JvmStatic
    public val state: State get() = Sdk.state

    /**
     * What the publisher knows about the viewer. Set it whenever you learn it, for example after login; the
     * next ads request carries it. Ads already loaded keep the targeting they were requested with.
     */
    @JvmStatic
    public var user: User?
        get() = Sdk.user
        set(value) {
            Sdk.checkMainThread("KontextVideo.user")
            Sdk.user = value
        }

    /**
     * Privacy and consent signals. Set what your consent platform did not write; `coppa` can only come from
     * you. `null` clears your values only: the SDK still reads the consent platform's storage on every request.
     */
    @JvmStatic
    public var regulatory: Regulatory?
        get() = Sdk.regulatory
        set(value) {
            Sdk.checkMainThread("KontextVideo.regulatory")
            Sdk.regulatory = value
        }

    /** The publisher's A/B test variant, if any; sent with every later request. */
    @JvmStatic
    public var variantId: String?
        get() = Sdk.variantId
        set(value) {
            Sdk.checkMainThread("KontextVideo.variantId")
            Sdk.variantId = value
        }

    /**
     * Starts the SDK: stores the configuration and calls the server in the background. Call it once, at app
     * launch; later calls are ignored. [listener] is told once the server answered or failed.
     */
    @MainThread
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, configuration: Configuration, listener: InitializationListener? = null) {
        Sdk.initialize(context, configuration, listener?.let { it::onInitialized })
    }

    /** Waits until `/config` has answered or failed, and returns the state; at once when it already has. Callable from any dispatcher. */
    public suspend fun awaitInitialization(): State = withContext(Dispatchers.Main.immediate) { Sdk.awaitInitialization() }
}
