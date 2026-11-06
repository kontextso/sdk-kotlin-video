package so.kontext.video.internal.screen

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import so.kontext.video.VideoAd
import so.kontext.video.VideoAd.EndReason
import so.kontext.video.VideoAdException
import so.kontext.video.internal.diagnostics.Logger
import so.kontext.video.internal.frame.FrameBridge
import so.kontext.video.internal.measurement.OnScreenTimeClock
import so.kontext.video.internal.measurement.ViewExposure
import so.kontext.video.internal.measurement.ViewabilityMeter
import so.kontext.video.internal.payloads.AdPayload
import so.kontext.video.internal.platform.AppActivities
import so.kontext.video.internal.platform.Environment
import java.lang.ref.WeakReference

/**
 * An overlay or pause ad: the [InlineAdView] in the app's container, and the [OnScreenTimeClock] that counts its
 * time seen (and an overlay's wall-clock duration). It follows the Activity holding the container.
 *
 * Placement: where the ad goes in the app's container. Two layers: this class decides, AdGeometry calculates.
 *
 * ```
 *   VideoAd.show(container, insets)  ─┐
 *   InlineAdView.onLayout (each pass)  ─┤
 *                                     ▼
 *   ┌─ InlineScreen (knows this ad) ────────────────────────────────┐
 *   │  combinedInsets  system bars + the app's insets               │
 *   │  chooseSize      which of the server's variants   (at show)   │
 *   │  refit           where it goes now                (each pass) │
 *   │  tooSmall        the ContainerTooSmall error                  │
 *   │  clearOfBars     full-screen pause ad: all but the bars        │
 *   └──────────────────────────────┬────────────────────────────────┘
 *                                  │ asks with plain numbers
 *                                  ▼
 *   ┌─ AdGeometry (knows no ad, pure arithmetic) ───────────────────┐
 *   │  room           the container less the insets                 │
 *   │  scale          how much the ad must shrink, at most 1        │
 *   │  smallestScale  0.5, or 1 for a fixed ad                      │
 *   │  frame          the rectangle: room, scale, check, place      │
 *   │  minimum        the smallest container that would do          │
 *   └───────────────────────────────────────────────────────────────┘
 * ```
 *
 * 1. At show, once: chooseSize. AdGeometry.room gives the free space (none: ContainerTooSmall). For each size
 *    variant from the server AdGeometry.scale says how much it would shrink; the pick is the biggest variant that
 *    fits at full size, else the one that shrinks least within its limit, else the closest (which then fails).
 *    AdGeometry.frame checks it; null: tooSmall reports AdGeometry.minimum and show throws, the ad stays ready.
 *    The pick is kept as the inline screen's chosenSize, next to the app's insets (extraInsets).
 * 2. At every layout pass of InlineAdView: refit. A full-screen pause ad takes the container less the bars, no
 *    variants. Otherwise AdGeometry.frame(chosenSize, …, combinedInsets(system)) gives where InlineAdView puts the
 *    WebView; null: it no longer fits, InlineAdView calls onUnfit and the ad ends INTERRUPTED.
 *
 * Example: variants 320×80 and 300×50 dp, density 3, a player of 800×600 px, nav bar 48 px, app controls 120 px.
 *   combinedInsets: bottom 168, so room is 800×432.
 *   chooseSize: 320×80 is 960×240 px, fits at 0.83; 300×50 is 900×150 px, fits at 0.89. Neither fits at full
 *   size, so the one that shrinks least: 300×50.
 *   refit: 300×50 at 0.89 is 800×133 px, centred, just above the bottom 168 px. A rotation runs refit again, same
 *   variant, new numbers.
 *
 * The split keeps AdGeometry testable with numbers alone, and the ad's rules (variants, full screen, errors,
 * logs) here.
 */
internal class InlineScreen(
    ad: VideoAd,
    /** The app's own insets from `show(container, insets)`, in pixels, never negative; kept through resizes. */
    val extraInsets: Rect,
) : AdScreen(ad) {
    /** The view the SDK put into the app's container; gone once the ad ends, or the container refused it. */
    var hostView: InlineAdView? = null

    /** The variant `chooseSize` picked from the ad's sizes at show (dp); `null` for a full-screen pause ad. */
    var chosenSize: AdPayload.Size? = null

    /** The Activity holding the container, while the ad is on screen. */
    var holdingActivity: WeakReference<Activity>? = null
        private set

    /** Time seen on screen; it also ends an overlay whose wall-clock duration elapsed. From [reachedScreen] to [tearDown]. */
    var onScreenClock: OnScreenTimeClock? = null
        private set

    /** The container's display density, read by [prepare]: the page's sizes are in dp. */
    private var density = 1f

    // The host: it has an area only where the container gives the ad room, and places the page.
    override val impressionTarget: View? get() = hostView
    override val isPlaced: Boolean get() = hostView?.hasPlacedContent ?: true

    /**
     * Before the slot is taken, nothing changed yet: [container] must be on screen, and a variant must fit it.
     * Throws `CannotShowNow` or `ContainerTooSmall`; the ad stays ready.
     */
    fun prepare(container: ViewGroup) {
        if (!ViewExposure.canBeSeen(container)) {
            Logger.warning { "ad ${ad.id.take(8)}: cannot show, the container is not on screen or is hidden" }
            throw VideoAdException.CannotShowNow()
        }
        // Attached and laid out is not on screen: a pager's neighbour page, a view scrolled away, a screen behind
        // another or an app in the background. The impression would count for nobody: refused, the ad ready.
        if (!isOnScreen(container)) throw VideoAdException.CannotShowNow()
        warnIfNotOverlaying(container)
        density = container.resources.displayMetrics.density
        // Picks the variant, or throws `ContainerTooSmall`, for where the host will be: the container's content
        // area, inside its padding, less the bars that reach into it and the app's insets. In the container's
        // pixels, so the minimum is a container size.
        val system = container.systemInsets
        val contentArea = Rect(
            maxOf(container.paddingLeft, system.left),
            maxOf(container.paddingTop, system.top),
            maxOf(container.paddingRight, system.right),
            maxOf(container.paddingBottom, system.bottom),
        )
        chooseSize(container.width, container.height, combinedInsets(contentArea), density)
    }

    /**
     * After the slot is taken: [bridge]'s page goes into a new host in [container]. Runs the container's own
     * hooks, which may throw (a container that refuses a child: a ScrollView with its child, a ListView); then
     * the ad undoes the show ([releaseRefusedHost]). Says `false` when the ad ended meanwhile.
     */
    fun addHost(container: ViewGroup, bridge: FrameBridge): Boolean {
        bridge.useShowingContext(container.context)
        val webView = bridge.webView
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (ad.hasEnded) return false
        // A full-screen pause ad's page stops at the bars (see refit): its host fills the strips behind
        // them and takes their touches, so the paused frame and the player stay covered as on iOS.
        val host = InlineAdView(container.context, webView, coversWhole = ad.payload.position == AdPayload.Position.FULLSCREEN)
        host.place = { width, height, system -> refit(width, height, system, density) }
        attach(host)
        container.addView(host)
        return true
    }

    /** The container refused the host: the host and the page leave it, and the page holds no Activity. */
    fun releaseRefusedHost(container: ViewGroup) {
        val host = hostView
        hostView = null
        if (host != null) {
            // A container that refuses a child may refuse its removal too.
            runCatching { (host.parent as? ViewGroup)?.removeView(host) }
            if (host.parent === container) runCatching { container.removeViewInLayout(host) }
            host.removeAllViews()
        }
        ad.loadedBridge?.let { bridge ->
            runCatching { (bridge.webView.parent as? ViewGroup)?.removeView(bridge.webView) }
            bridge.useApplicationContext()
        }
    }

    /**
     * The host is still where it was added: a destroy in the container's hook ended the ad and took its host
     * (`false`); the container's own hook may have removed the host, or taken the container off screen: the ad
     * ends, interrupted (`false`).
     */
    fun isStillOnScreen(container: ViewGroup): Boolean {
        val host = hostView ?: return false
        if (!ad.hasEnded && (host.parent !== container || host.visibility != View.VISIBLE || !ViewExposure.canBeSeen(container) || ViewExposure.isTransparent(host))) {
            Logger.warning { "ad ${ad.id.take(8)}: the ad left the screen as it was added; ended as interrupted" }
            ad.end(EndReason.INTERRUPTED)
            return false
        }
        return true
    }

    /**
     * Whether any of the page can be seen where it will be placed, asked at `show` before the ad reaches the
     * screen, as Swift asks at attach: an ad nobody can see (a feed scrolled so the player's top shows, not the
     * ad's card at its bottom) ends with no `creativeView` or `iconView`. Android lays the host out only at the
     * next pass, so the page's frame is worked out as that pass will (the container inside its padding, [refit])
     * and compared with the part of the container that is seen. A page given no room is left to the layout
     * passes (`onUnfit`), as before.
     */
    fun isSeenWherePlaced(container: ViewGroup): Boolean {
        val width = container.width - container.paddingLeft - container.paddingRight
        val height = container.height - container.paddingTop - container.paddingBottom
        val frame = refit(width, height, container.systemInsets, density) ?: return true
        if (frame.isEmpty) return true
        frame.offset(container.paddingLeft, container.paddingTop)
        val seen = ViewExposure.measure(container)
        return seen.share > 0 && Rect.intersects(seen.visibleBox, frame)
    }

    /** `show` made [host] for the container: the screen hears its fit and its window. */
    fun attach(host: InlineAdView) {
        hostView = host
        host.onUnfit = {
            if (!ad.hasEnded) {
                Logger.warning {
                    "ad ${ad.id.take(8)}: the container became too small for the ad, or gives it no room (not a FrameLayout-like container); " +
                        "ended as interrupted"
                }
                ad.end(EndReason.INTERRUPTED)
            }
        }
        host.onWindowVisibility = { visible -> onScreenClock?.windowVisibilityChanged(visible) }
    }

    /**
     * The meter is the one answer to "is it seen": the clock counts only then, and an ad nobody can see while
     * the app is in front (a feed cell swiped away) ends and frees the slot.
     */
    override fun configure() {
        val meter = meter ?: return
        meter.onSeen = { seen -> onScreenClock?.seenChanged(seen) }
        meter.isActive = { Environment.isAppInForeground() && hostView?.windowVisibility == View.VISIBLE }
        // Android has no lifecycle event for picture-in-picture: the meter's tick asks. An inline ad in a
        // thumbnail would count its viewability and time there; it ends, as at show it would be refused.
        meter.onTick = {
            if (!ad.hasEnded && holdingActivity?.get()?.isInPictureInPictureMode == true) {
                Logger.warning { "ad ${ad.id.take(8)}: its Activity entered picture-in-picture; ended as interrupted" }
                ad.end(EndReason.INTERRUPTED)
            }
        }
        meter.onOffScreen = {
            if (!ad.hasEnded) {
                Logger.warning { "ad ${ad.id.take(8)}: nothing of it seen for ${ViewabilityMeter.offScreenGraceMillis} ms; ended as interrupted" }
                ad.end(EndReason.INTERRUPTED)
            }
        }
    }

    /**
     * 1. The clock starts, with the content rule's wall-clock duration (an overlay with no tracked player).
     * 2. The content rule starts (an overlay counts video time from the playhead now).
     * 3. The ad follows the Activity holding the container.
     */
    override fun reachedScreen() {
        val clock = OnScreenTimeClock(ad.contentRule.wallClockDurationMillis, ad.id) {
            if (ad.hasEnded) return@OnScreenTimeClock
            Logger.debug { "ad ${ad.id.take(8)}: overlay duration of ${ad.durationMillis ?: 0} ms on screen elapsed" }
            ad.state.markCompleted()
            ad.end(EndReason.COMPLETED)
        }
        this.onScreenClock = clock
        clock.start(isVisible = hostView?.windowVisibility == View.VISIBLE, isSeen = meter?.isSeen ?: true)
        ad.contentRule.started(clock)
        hostView?.activity?.let(::followHoldingActivity)
    }

    override fun tearDown(): Long {
        val activeTime = onScreenClock?.stop() ?: 0
        // The stopped clock's callback reaches the owner's playback: an ended ad an app keeps must not keep its VideoAds.
        onScreenClock?.onActivate = null
        onScreenClock = null
        meter?.stop()
        hostView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        hostView = null
        meter = null
        return activeTime
    }

    override fun close(onGone: () -> Unit): Boolean = false

    /** The system's and the app's insets together; saturated, so a huge app inset cannot wrap around to a small one. */
    fun combinedInsets(system: Rect): Rect {
        fun add(a: Int, b: Int) = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return Rect(add(system.left, extraInsets.left), add(system.top, extraInsets.top), add(system.right, extraInsets.right), add(system.bottom, extraInsets.bottom))
    }

    /** Picks the variant for the container: the biggest that fits unscaled, else the one shrunk least. Throws `ContainerTooSmall`. */
    fun chooseSize(width: Int, height: Int, insets: Rect, density: Float) {
        val payload = ad.payload
        val defaultSize = payload.sizes.firstOrNull()
        if (payload.position == AdPayload.Position.FULLSCREEN || defaultSize == null) return
        val room = AdGeometry.room(width, height, insets) ?: run {
            chosenSize = null
            throw tooSmall(defaultSize, insets, density, "the insets leave no room for")
        }
        val fit = { candidate: AdPayload.Size -> AdGeometry.scale(candidate, room, density) }
        val size = payload.sizes.filter { fit(it) >= 1 }.maxByOrNull { it.width * it.height }
            ?: payload.sizes.filter { fit(it) >= AdGeometry.smallestScale(it) }.maxByOrNull(fit)
            ?: payload.sizes.maxByOrNull(fit)
            ?: defaultSize
        chosenSize = size
        if (AdGeometry.frame(size, payload.position, width, height, insets, density) == null) {
            chosenSize = null
            throw tooSmall(size, insets, density, "a $width×$height px container is too small for")
        }
    }

    private fun tooSmall(size: AdPayload.Size, insets: Rect, density: Float, what: String): VideoAdException {
        val minimum = AdGeometry.minimum(size, insets, density)
        val limit = if (size.isScalable) " at 50 %" else ", which may not shrink"
        Logger.warning { "ad ${ad.id.take(8)}: $what the ad's ${size.width}×${size.height} dp$limit; the ad stays ready" }
        return VideoAdException.ContainerTooSmall(minimum.width, minimum.height)
    }

    fun refit(width: Int, height: Int, system: Rect, density: Float): Rect? {
        val payload = ad.payload
        // A full-screen pause ad clears the system bars and the cutout: Android's WebView gives the page no
        // safe-area insets, so a page laid out edge to edge would put its controls under the clock. The host
        // still covers the whole container behind the bars.
        if (payload.position == AdPayload.Position.FULLSCREEN) return clearOfBars(width, height, system)
        val size = chosenSize ?: return Rect(0, 0, width, height)
        return AdGeometry.frame(size, payload.position, width, height, combinedInsets(system), density)
    }

    /** The container less the system bars and cutout that overlap it; the whole container when they leave no room. */
    private fun clearOfBars(width: Int, height: Int, system: Rect): Rect {
        val frame = Rect(system.left, system.top, width - system.right, height - system.bottom)
        return if (frame.width() > 0 && frame.height() > 0) frame else Rect(0, 0, width, height)
    }

    /**
     * Another Activity of the app covers the one holding the container (it stopped while the app stays in
     * front): the ad ends, interrupted, as on iOS, where a full-screen cover takes the presenter's view out of
     * its window. The app going to the background only freezes the ad's clock (and an overlay's content
     * pausing there, see `OverlayRule`); a stop for a configuration change recreates the Activity, and the
     * container leaving the window ends the ad there.
     */
    /** A container that lays its children out one after another gives the ad no room over the player. */
    private fun warnIfNotOverlaying(container: ViewGroup) {
        if (container !is LinearLayout && container !is GridLayout) return
        Logger.warning {
            "ad ${ad.id.take(8)}: the container is a ${container.javaClass.simpleName}, which places the ad beside the player, not over it; " +
                "use a FrameLayout-like container"
        }
    }

    /** Some of [container] is inside its window, its Activity is started and the app is in front. Says why not. */
    private fun isOnScreen(container: ViewGroup): Boolean {
        val why = when {
            ViewExposure.measure(container).share <= 0 -> "no part of the container is inside its window (a neighbour page, scrolled away)"
            !Environment.isAppInForeground() -> "the app is not in front"
            container.activity?.let { it.isFinishing || it.isDestroyed || AppActivities.isStarted(it) == false } == true -> "its Activity is not visible"
            container.activity?.isInPictureInPictureMode == true -> "its Activity is in picture-in-picture"
            else -> return true
        }
        Logger.warning { "ad ${ad.id.take(8)}: cannot show, $why; the ad stays ready" }
        return false
    }

    private fun followHoldingActivity(holder: Activity) {
        holdingActivity = WeakReference(holder)
        follow(holder) { activity, event ->
            if (event == AppActivities.Event.RESUMED) ad.contentRule.holdingActivityResumed()
            if (event == AppActivities.Event.STOPPED && !activity.isChangingConfigurations && Environment.isAppInForeground()) {
                Logger.warning { "ad ${ad.id.take(8)}: another screen of the app covers the ad's; ended as interrupted" }
                ad.end(EndReason.INTERRUPTED)
            }
        }
    }
}
