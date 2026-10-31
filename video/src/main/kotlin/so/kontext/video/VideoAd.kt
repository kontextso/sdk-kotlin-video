package so.kontext.video

import android.app.Activity
import android.view.ViewGroup
import androidx.annotation.MainThread
import androidx.core.graphics.Insets

/**
 * One ad, from the server's answer to the end of its presentation. Look at its facts ([price], [format],
 * [durationMillis]), then [load] or [reject], then `show`, then exactly one [VideoAdListener.onAdEnded].
 * Shown at most once. Every call is main-thread.
 */
public class VideoAd internal constructor() {
    /** The server's id of the ad, for support. */
    public val id: String get() = TODO()

    /** The format it was requested for. */
    public val format: AdFormat get() = TODO()

    /** What it pays, known before anything renders. */
    public val price: Price get() = TODO()

    /**
     * Overlay: how long the SDK keeps it on screen, `null` when it stays until ended. Linear: the video's
     * length. Always `null` for a pause ad. Milliseconds.
     */
    public val durationMillis: Long? get() = TODO()

    /** `load()` finished: the creative is rendered off screen. */
    public val isLoaded: Boolean get() = TODO()

    /** Loaded and still usable: `show` will not throw `NotLoaded` or `Invalidated`. */
    public val isReady: Boolean get() = TODO()

    /** Why the ad ended before it was shown, once it did. */
    public val invalidationReason: InvalidationReason? get() = TODO()

    /** `show` succeeded once. */
    public val wasShown: Boolean get() = TODO()

    /** The ad began to end; `onAdEnded` follows at once for an overlay or pause ad, once its screen is gone for a linear ad. */
    public val hasEnded: Boolean get() = TODO()

    /** The creative ran to its end: an overlay's duration elapsed, a linear video played through. */
    public val isCompleted: Boolean get() = TODO()

    /** The moments on screen. Held until `onAdEnded` has run. */
    public var listener: VideoAdListener?
        get() = TODO()
        set(value) = TODO()

    /**
     * Renders the creative off screen. Returns once it can be shown; throws [VideoAdException]
     * (`CreativeFailed`, `Invalidated`, `Destroyed`, ...). Idempotent: a second call waits for the first.
     */
    @MainThread
    public suspend fun load(): Unit = TODO()

    /** [load] for callers without coroutines: [callback] gets this ad, or the exception, on the main thread. */
    @MainThread
    public fun load(callback: VideoAdsCallback<VideoAd>): Unit = TODO()

    /** You won't use the ad; the server tells the advertiser. Works until `show`, loaded or not. */
    @MainThread
    @Throws(VideoAdException::class)
    @JvmOverloads
    public fun reject(reason: RejectReason = RejectReason.NotUsed): Unit = TODO()

    /**
     * Shows an overlay or pause ad inside [container], the view the content plays in. Returns once the ad is
     * on screen. [insets] (pixels) keep it clear of your own controls, added to the container's system
     * insets. Throws, with nothing shown: `NotLoaded`, `AlreadyShown`, `AnotherAdShowing`, `Invalidated`,
     * `Destroyed`, `Disabled`, `PresenterUnavailable`, `ContainerTooSmall`, `ContentPaused`, `WrongPresentation`.
     */
    @MainThread
    @Throws(VideoAdException::class)
    @JvmOverloads
    public fun show(container: ViewGroup, insets: Insets = Insets.NONE): Unit = TODO()

    /** Shows a linear ad full screen, in the SDK's own Activity started from [activity]. */
    @MainThread
    @Throws(VideoAdException::class)
    public fun show(activity: Activity): Unit = TODO()

    /** Ends an overlay or pause ad because you say so; `onAdEnded(DISMISSED)` follows, synchronously. Before `show` and after the end it does nothing. */
    @MainThread
    @Throws(VideoAdException::class)
    public fun dismiss(): Unit = TODO()

    override fun toString(): String = TODO()

    /** What an ad pays: a CPM in a currency, and whether that figure is a clearing price or a forecast. */
    public class Price internal constructor(
        /** Per 1,000 impressions. */
        public val value: Double,
        /** ISO 4217. */
        public val currency: String,
        /** Whether [value] is a DSP's clearing price or a forecast. */
        public val precision: Precision,
    ) {
        /** How firm [value] is. */
        public enum class Precision {
            /** A DSP's clearing price. */
            EXACT,

            /** Our own or house demand, a forecast. */
            ESTIMATED,
        }

        override fun equals(other: Any?): Boolean =
            other is Price && value == other.value && currency == other.currency && precision == other.precision

        override fun hashCode(): Int = listOf(value, currency, precision).hashCode()

        override fun toString(): String = "Price($value $currency, $precision)"
    }

    /** Why the app did not use an ad; the server tells the DSP. */
    public sealed class RejectReason {
        /** The app does not use it, for any reason: the moment passed, the viewer left, the app's own choice. */
        public object NotUsed : RejectReason() {
            override fun toString(): String = "NotUsed"
        }

        /** Another network won the publisher's auction; [winningPrice] (CPM) and [winner] (the network, in the app's own words) when known. */
        public class Outbid @JvmOverloads constructor(
            public val winningPrice: Double? = null,
            public val winner: String? = null,
        ) : RejectReason() {
            override fun equals(other: Any?): Boolean = other is Outbid && winningPrice == other.winningPrice && winner == other.winner

            override fun hashCode(): Int = listOf(winningPrice, winner).hashCode()

            override fun toString(): String = "Outbid(winningPrice=$winningPrice, winner=$winner)"
        }
    }

    /** Why an ad ended before it was shown. Found out at the next call (`format`, `load`, `show`, `reject`), which throws it. */
    public sealed class InvalidationReason {
        /** The ad's lifetime ran out (or the server replaced it, which it does only after that). */
        public object Expired : InvalidationReason() {
            override fun toString(): String = "Expired"
        }

        /** The app called `reject()`. */
        public object Rejected : InvalidationReason() {
            override fun toString(): String = "Rejected"
        }

        /** The `VideoAds` was destroyed. */
        public object Destroyed : InvalidationReason() {
            override fun toString(): String = "Destroyed"
        }

        /** The creative failed to load, or died while the ad waited to be shown. [code] is the VAST error code the SDK fired. */
        public class CreativeFailed(public val code: Int) : InvalidationReason() {
            override fun equals(other: Any?): Boolean = other is CreativeFailed && code == other.code

            override fun hashCode(): Int = code

            override fun toString(): String = "CreativeFailed($code)"
        }
    }

    /** A tap on the ad. It carries no URL: the SDK has already opened the target. */
    public class Click internal constructor(
        /** Where the viewer tapped. */
        public val area: Area,
    ) {
        /** Where the viewer tapped. */
        public sealed class Area {
            /** The creative itself: the image, the video, the card. */
            public object Creative : Area() {
                override fun toString(): String = "Creative"
            }

            /** A linear ad's end card, after the video completed. */
            public object EndCard : Area() {
                override fun toString(): String = "EndCard"
            }

            /** A label of the page's own (`cta`, `logo`, ...), passed through unchanged. */
            public class Other(public val label: String) : Area() {
                override fun equals(other: Any?): Boolean = other is Other && label == other.label

                override fun hashCode(): Int = label.hashCode()

                override fun toString(): String = "Other($label)"
            }

            internal companion object {
                /** From the page's `area`: absent is the creative. */
                fun from(raw: String?): Area = when (raw) {
                    null, "creative" -> Creative
                    "endCard" -> EndCard
                    else -> Other(raw)
                }
            }
        }

        override fun equals(other: Any?): Boolean = other is Click && area == other.area

        override fun hashCode(): Int = area.hashCode()

        override fun toString(): String = "Click($area)"
    }

    /**
     * How an ad on screen ended: who or what took it off the screen. Whether the creative ran to its end is a
     * separate fact, `VideoAd.isCompleted`: a linear ad whose end card the viewer closes is [CLOSED] with
     * `isCompleted == true`.
     */
    public enum class EndReason {
        /** The ad ended by itself: an overlay's duration elapsed; a linear ad without an end card ended after its video completed. */
        COMPLETED,

        /** The viewer tapped a linear ad's Skip (or close) before the video ended. */
        SKIPPED,

        /** The viewer tapped the ad's close control: an overlay's or pause card's ✕ (the content stays paused), or a linear ad's end card ✕. */
        CLOSED,

        /** The viewer tapped Resume on a full-screen pause ad. */
        RESUMED,

        /** The ad broke after `show` returned; `onAdFailed` came first. */
        FAILED,

        /**
         * The presentation disappeared for a reason the SDK did not cause: the container left the window or
         * became too small for the ad, the linear ad's screen was destroyed under it. For an overlay with a
         * tracked player, also the content pausing, ending or moving to another item.
         */
        INTERRUPTED,

        /** The app called `dismiss()`. */
        DISMISSED,

        /** The app called `destroy()` on the `VideoAds` while the ad was on screen. */
        DESTROYED,
    }
}
