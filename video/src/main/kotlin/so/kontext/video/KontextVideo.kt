package so.kontext.video

import android.content.Context
import androidx.annotation.MainThread

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
    public val state: State get() = TODO()

    /** What the publisher knows about the viewer; sent with every later request. */
    @JvmStatic
    public var user: User?
        get() = TODO()
        set(value) = TODO()

    /** Consent signals the consent platform did not write, and COPPA; sent with every later request. */
    @JvmStatic
    public var regulatory: Regulatory?
        get() = TODO()
        set(value) = TODO()

    /** The publisher's A/B test variant, if any; sent with every later request. */
    @JvmStatic
    public var variantId: String?
        get() = TODO()
        set(value) = TODO()

    /**
     * Starts the SDK: stores the configuration and calls the server in the background. Call it once, at app
     * launch; later calls are ignored. [listener] is told once the server answered or failed.
     */
    @MainThread
    @JvmStatic
    @JvmOverloads
    public fun initialize(context: Context, configuration: Configuration, listener: InitializationListener? = null): Unit = TODO()

    /** Waits until `/config` has answered or failed, and returns the state; at once when it already has. */
    @MainThread
    public suspend fun awaitInitialization(): State = TODO()
}
