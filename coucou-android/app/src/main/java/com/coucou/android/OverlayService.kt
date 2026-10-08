package com.coucou.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors

/**
 * Foreground service that owns the floating bubble.
 *
 * The bubble is a [WindowManager] overlay of type [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * so it can draw above other apps. Responsibilities:
 *  - keep itself alive as a foreground service (survives the home screen)
 *  - add/update/remove the overlay view
 *  - drag the bubble, and treat a tap (vs. a drag) as "expand"
 *  - swap between the collapsed bubble and the expanded ask bar
 *  - drive the procedural character's state machine ([CoucouCharacterView]) and its sounds
 *
 * The service is deliberately the only owner of the window: [onDestroy] removes the
 * view so a stopped service can never leave an orphan overlay on screen.
 */
class OverlayService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Handler for auto-close background operations. */
    private val autoCloseHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var router: CommandRouter? = null
    private var appLauncher: AppLauncher? = null

    /** Null-safe: no-ops until @Buffy's sound assets land. See [SoundPlayer]. */
    private var soundPlayer: SoundPlayer? = null

    /** @Cline's layouts host this; resolved reflectively and optional in both layouts. */
    private var characterView: CoucouCharacterView? = null

    /**
     * Sprint 3 REDO: the desktop prompt box in a WebView, and the bridge that stands in
     * for Tauri. Null when the WebView could not be created — a device with no WebView
     * provider still gets the collapsed rectangle, just nothing to expand into.
     */
    private var island: CoucouIslandWebView? = null
    private var islandBridge: IslandBridgeHost? = null

    /** Kept so the WebView can report a load failure back without going through the bridge. */
    private var islandListener: IslandListener? = null

    /**
     * The one attached window root, holding the rectangle and the prompt box as siblings.
     *
     * Both children outlive an expand/collapse cycle: the WebView must survive (a fresh
     * one costs a WebView spawn plus a page load, which is the delay the REDO brief's
     * "expanding is instant" rules out) and the rectangle must survive so its dragged
     * position and character state do not reset.
     */
    private var container: OverlayHostLayout? = null

    /** The collapsed rectangle, inflated once and re-used. */
    private var bubbleView: View? = null

    

    /** Says what went wrong instead of leaving an empty window on screen. */
    private var errorView: TextView? = null

    /** True once the page has failed to load, so the error view takes the island's place. */
    private var islandFailed = false

    /** Island geometry pushed by the page (CSS px), applied to [layoutParams] on the main thread. */
    private var islandWidthCss = 0.0
    private var islandHeightCss = 0.0

    /** True once the page has pushed a rect, i.e. the prompt box has a height to open at. */
    private var islandSized = false

    /** True once the window has been attached off screen to boot the page. */
    private var islandWarm = false

    /** An expand arrived before the page had a rect; honoured as soon as it pushes one. */
    private var revealPending = false

    /** True while the page's own top-bar drag handle is moving the window. */
    private var pageDragging = false

    /** Whether the window may take keyboard focus right now; mirrors the chat field. */
    private var islandFocused = false

    private var isExpanded = false
    private var isGreetingActive = false
    /** Set to true when overlay collapses due to auto-close timer. */
    private var autoClosed = false
    private var isDragging = false
    private var hasGreeted = false

    /** Screen state as far as we know; drives the character's pause rule (report §2.5). */
    private var isScreenOn = true

    /** Kept so the receiver can be unregistered exactly once, in [onDestroy]. */
    private var screenReceiverRegistered = false
    private var screenReceiver: BroadcastReceiver? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    /** Starts the auto-close timer (15s inactivity). Resets on any bubble interaction. */
    private fun startAutoCloseTimer() {
        stopAutoCloseTimer()
        autoCloseHandler.postDelayed({ autoClosed = true; collapse() }, AUTO_CLOSE_DELAY_MS)
    }

    /** Cancels the auto-close timer. */
    private fun stopAutoCloseTimer() {
        autoCloseHandler.removeCallbacksAndMessages(null)
    }

    /** Sets autoClosed flag and collapses; differentiates auto-dock vs user-dismiss. */
    private fun requestAutoCollapse() {
        autoClosed = true
        collapse()
    }

    companion object {
        const val CHANNEL_ID = "coucou_overlay_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.coucou.android.ACTION_START"
        const val ACTION_STOP = "com.coucou.android.ACTION_STOP"
        const val ACTION_EXPAND = "com.coucou.android.ACTION_EXPAND"
        const val ACTION_COLLAPSE = "com.coucou.android.ACTION_COLLAPSE"
        const val ACTION_COMMAND = "com.coucou.android.ACTION_COMMAND"

        const val EXTRA_COMMAND = "extra_command"

        const val TAG = "CoucouOverlayService"

        /** Layout name owned by @Cline, resolved reflectively so this compiles standalone. */
        const val LAYOUT_COLLAPSED = "overlay_bubble"

        

        private const val ID_PREFIX = "coucou_"
        private const val KEYBOARD_SHOW_DELAY_MS = 150L
        private const val COLLAPSE_DELAY_MS = 400L
        private const val AUTO_CLOSE_DELAY_MS = 15_000L

        /**
         * Grace period between the page reporting ready and revealing the island, so the
         * reveal lands after the boot sequence has finished wiring its listeners.
         */
        private const val ISLAND_REVEAL_DELAY_MS = 300L

        /** How long a transient character state (thinking/finished/error) stays on screen. */
        private const val CHARACTER_RESET_MS = 1600L

        /** The load-failure box has to be readable from across a desk. */
        private const val ERROR_TEXT_SIZE_SP = 14f

        /**
         * Height, in dp, of the off-screen window the page boots in.
         *
         * Generous on purpose: the page lays out its prompt view in whatever viewport it is
         * given, and too small a one would make it push a rect for the wrong shape.
         */
        private const val WARMUP_HEIGHT_DP = 400

        /**
         * How long to wait for the page's first rect before assuming one.
         *
         * The page is the only source of the window's height, so an unbounded wait means a
         * tap can do nothing at all.
         */
        private const val ISLAND_BOOT_TIMEOUT_MS = 1500L

        /** Prompt-box height assumed if the page never measures itself, in dp. */
        private const val FALLBACK_PROMPT_HEIGHT_DP = 320.0

        var isRunning: Boolean = false
            private set

        /** True while the ask bar is showing, for diagnostics and the pass checklist. */
        var isExpandedState: Boolean = false
            private set

        /**
         * Wire name of the character state currently on screen (`idle`, `thinking`, …), for
         * @AGY's emulator pass — the character itself cannot be asserted from logcat.
         */
        var characterStateName: String = CoucouState.IDLE.wireName
            private set

        /** Screen state last broadcast to the service; `false` means animation is paused. */
        var screenOn: Boolean = true
            private set

        /** Full character diagnostic for @AGY's emulator pass, e.g.
         * `adb shell ... | grep "CoucouOverlayService"`. Empty when no character view is bound.
         */
        var characterDescription: String = ""
            private set

        /**
         * Island (Plan A) diagnostic for @AGY's emulator pass: whether the WebView is
         * hosting, the rect it last pushed and whether it holds keyboard focus.
         */
        var islandDescription: String = "island: off"
            private set

        /** True while the WebView island is hosting the overlay instead of the native bubble. */
        var islandHosting: Boolean = false
            private set

        /** Id names @Cline may give the character view; resolved reflectively, all optional. */
        private val CHARACTER_IDS = listOf("character", "character_view")

        /** States that fall back to a resting state on their own. */
        private val TRANSIENT_STATES = setOf(
            CoucouState.THINKING,
            CoucouState.WORKING,
            CoucouState.FINISHED,
            CoucouState.ERROR,
            CoucouState.DIZZY
        )

        /** Starts the service (no-op if already running). */
        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_START }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        /** Sends text to the overlay as if the user had typed it. */
        fun sendCommand(context: Context, text: String) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_COMMAND
                putExtra(EXTRA_COMMAND, text)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        appLauncher = AppLauncher(this)
        router = DefaultCommandRouter(listOf(
                AssistantRouter(this),
                LaunchAppCommandRouter(appLauncher!!)
            ))
        soundPlayer = SoundPlayer(this).also { it.preloadAvailable() }
        islandListener = IslandListener()
        islandBridge = IslandBridgeHost(this, islandListener!!)
        registerScreenReceiver()
    }

    /**
     * What the island asks the window for.
     *
     * Every method here is called on the WebView's JavaScript thread, so each one hops to
     * the main thread before touching [layoutParams] or the window. `onChatQuery` is the
     * exception: the page is blocked on its answer, and [CommandRouter] is synchronous.
     */
    private inner class IslandListener : IslandBridgeHost.Listener {

        override fun onIslandRect(widthCss: Double, heightCss: Double) {
            // A zero height is the page retracting. The collapsed shape is the native
            // rectangle now, so there is no wake strip to shrink to and nothing to apply.
            if (heightCss <= 0) return
            islandWidthCss = widthCss
            islandHeightCss = heightCss
            mainHandler.post { onIslandSized() }
        }

        override fun onIslandCollapsed(collapsed: Boolean) {
            // The page hid itself (its auto-close timer). The collapsed shape is the native
            // rectangle, so that is what the window has to become.
            if (collapsed) mainHandler.post { collapse() }
        }

        override fun onDragStart() {
            mainHandler.post { pageDragging = true }
        }

        override fun onDragBy(dx: Double, dy: Double) {
            mainHandler.post { dragWindowBy(dx, dy) }
        }

        override fun onDragEnd() {
            mainHandler.post {
                pageDragging = false
                val params = layoutParams ?: return@post
                clampOnScreen(params)
                pushLayout()
            }
        }

        override fun onIslandFocus(focused: Boolean) {
            islandFocused = focused
            mainHandler.post { setIslandFocus(focused) }
        }

        override fun onChatQuery(query: String): String {
            // Runs on the JS thread: routing is blocking PackageManager work, which is
            // exactly what the page is waiting for.
            val result = router?.route(query)
            val text = when (result) {
                is CommandResult.Success -> result.message ?: query
                is CommandResult.Failed -> getString(R.string.command_failed, result.reason)
                else -> "I'm not sure what you mean. Try 'hello', 'open <app>', 'note: ...', or 'search <query>'."
            }
            setCharacterState(
                if (result is CommandResult.Success) CoucouState.FINISHED else CoucouState.ERROR,
                if (result is CommandResult.Success) SoundPlayer.Sound.SEND else SoundPlayer.Sound.ERROR
            )
            Log.i(TAG, "Island command '$query' -> $result")
            mainHandler.post {
                val cleanResponse = text.replace("'", "\\'").replace("\n", " ")
                island?.view?.evaluateJavascript(
                    "window.CoucouAndroid && window.CoucouAndroid.onChatResponse && window.CoucouAndroid.onChatResponse('$cleanResponse')",
                    null
                )
            }
            return text
        }

        override fun onOpenUrl(url: String) {
            mainHandler.post { openExternalUrl(url) }
        }

        override fun onSettingsChanged(settings: IslandBridgeCommands.IslandSettings) {
            Log.i(TAG, "Island settings -> sound=${settings.soundEnabled} " +
                "volume=${settings.soundVolume} autoClose=${settings.autoCloseSeconds}s")
            // The page applies its own settings on the echoed event; nothing else to do
            // natively, but the character sound follows the same switch.
            if (!settings.soundEnabled) releaseSounds()
        }

        override fun screen() = IslandBridgeCommands.Screen(
            widthPx = resources.displayMetrics.widthPixels,
            heightPx = resources.displayMetrics.heightPixels,
            density = resources.displayMetrics.density
        )

        override fun onPageLog(message: String) {
            Log.i(TAG, "Island page: $message")
        }

        override fun onPageFailed(message: String) {
            mainHandler.post { showOverlayError(message) }
        }

        override fun onQuitRequested() {
            mainHandler.post { stopOverlayService() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopOverlayService()
                return START_NOT_STICKY
            }
            ACTION_EXPAND -> {
                startAsForeground(showGreeting = false)
                expand()
                return START_STICKY
            }
            ACTION_COLLAPSE -> {
                collapse()
                return START_STICKY
            }
            ACTION_COMMAND -> {
                startAsForeground(showGreeting = false)
                val text = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                if (text.isNotBlank()) {
                    submitCommand(text)
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                startAsForeground(showGreeting = true)
            }
        }
        return START_STICKY
    }

    /**
     * Start the overlay with the authentic desktop top island greeting:
     * Positioned at TOP CENTER with dimensions width 350dp, height 92dp (aspect ratio ~3.7:1).
     * The WebView Overlay is added first, with collapsed bubble hidden.
     * Dimensions read from coucou_island_intro_width / coucou_island_intro_height resources.
     * Coerced to max screen width via coerceAtMost() to never fill the screen.
     * FLAG_NOT_FOCUSABLE is cleared on expand so the soft keyboard can pop up.
     */
    private fun startGreetingOverlay() {
        isGreetingActive = true
        isExpanded = true
        isExpandedState = true

        val root = ensureContainer() ?: return
        val params = layoutParams ?: buildLayoutParams().also { layoutParams = it }
        val metrics = resources.displayMetrics

        // Position it at the TOP CENTER of the screen
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = 0
        // Dimensions: width 350dp, height 92dp (aspect ratio ~3.7:1)
        params.width = resources.getDimensionPixelSize(R.dimen.coucou_island_intro_width).coerceAtMost(metrics.widthPixels)
        params.height = resources.getDimensionPixelSize(R.dimen.coucou_island_intro_height)
        params.flags = bubbleFlags()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED

        // Do NOT show the collapsed bubble on start: show WebView Overlay first
        bubbleView?.visibility = View.GONE
        island?.view?.apply {
            visibility = View.VISIBLE
            alpha = 1f
        }
        errorView?.visibility = View.GONE

        try {
            if (overlayView == null) {
                windowManager?.addView(root, params)
                overlayView = root
            } else {
                windowManager?.updateViewLayout(root, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place the greeting overlay window", e)
            overlayView = null
        }

        islandWarm = true
        islandHosting = true
        islandDescription = describeWindow(params)
        island?.resume()
        island?.load()
    }

    private fun startAsForeground(showGreeting: Boolean = false) {
        isRunning = true
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!Settings.canDrawOverlays(this)) {
            // Only add the overlay once we are actually foreground *and* permitted,
            // otherwise the window is rejected on Android 10+ (background window
            // restrictions) and again by the overlay permission check.
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            return
        }
        if (showGreeting) {
            startGreetingOverlay()
        } else {
            warmIsland()
            applyWindowState()
        }
    }

    // region overlay window

    /**
     * Puts the window into the shape the current state calls for and shows it.
     *
     * The two shapes share one attached root, so this is the only place that adds, resizes
     * or re-lays-out the window; everything else changes state and comes back here.
     */
    private fun applyWindowState() {
        val wm = windowManager ?: return
        val root = ensureContainer() ?: return
        val params = layoutParams ?: buildLayoutParams().also { layoutParams = it }
        val islandView = island?.view
        // A device with no WebView has nothing to expand into, so it stays a rectangle.
        val expanded = isExpanded && islandView != null
        isExpanded = expanded
        isExpandedState = expanded

        if (expanded) {
            applyPanelParams(params)
            bubbleView?.visibility = View.GONE
            islandView?.visibility = if (islandFailed) View.GONE else View.VISIBLE
            errorView?.visibility = if (islandFailed) View.VISIBLE else View.GONE
        } else {
            applyCollapsedParams(params)
            bubbleView?.visibility = View.VISIBLE
            islandView?.visibility = View.GONE
            errorView?.visibility = View.GONE
        }
        clampOnScreen(params)
        try {
            if (overlayView == null) {
                wm.addView(root, params)
                overlayView = root
            } else {
                wm.updateViewLayout(overlayView!!, params)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place the overlay window", e)
            overlayView = null
        }
        characterView?.setHostVisible(true)
        characterView?.let { characterDescription = CoucouCharacterView.describeState(it) }
        islandHosting = island != null
        islandDescription = describeWindow(params)
        if (expanded && !hasGreeted) {
            hasGreeted = true
            characterView?.greet()
        }
    }

    /**
     * The one attached root, with the rectangle and the prompt box as siblings.
     *
     * The prompt box child is `GONE` while collapsed, not `INVISIBLE`: a `GONE` child is
     * not measured, so the container's natural size is exactly the rectangle's and
     * `WRAP_CONTENT` gives a window that size — which is what lets touches beside the
     * rectangle reach the app underneath.
     */
    private fun ensureContainer(): OverlayHostLayout? {
        container?.let { return it }
        val bubble = bubbleView ?: inflateBubble().also { bubbleView = it }
        val root = OverlayHostLayout(
            this,
            onOutsideTouch = {
                if (isExpanded) {
                    collapse()
                }
            },
            onBackPressed = { collapse() }
        )
        val fill = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        bubble.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(bubble)
        if (island == null) inflateIsland()
        island?.view?.apply {
            layoutParams = fill
            visibility = View.GONE
            root.addView(this)
        }
        errorView = buildErrorView().also {
            it.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            it.visibility = View.GONE
            root.addView(it)
        }
        container = root
        return root
    }

    /**
     * The "the page did not load" box.
     *
     * An overlay that fails silently is the worst kind of bug to chase: the user sees a
     * floating window with nothing in it, and logcat says nothing either. @Boss's screen
     * recording was exactly that. So the failure is stated, in the same place the prompt
     * box would have been.
     */
    private fun buildErrorView(): TextView = TextView(ContextThemeWrapper(this, R.style.Theme_Coucou)).apply {
        setTextColor(ContextCompat.getColor(context, R.color.coucou_ink))
        setBackgroundColor(ContextCompat.getColor(context, R.color.coucou_card))
        textSize = ERROR_TEXT_SIZE_SP
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    /** Puts [message] on screen in place of the prompt box. */
    private fun showOverlayError(message: String) {
        islandFailed = true
        Log.e(TAG, "Island page failed: $message")
        islandDescription = "island: failed ($message)"
        val text = errorView ?: ensureContainer()?.let { errorView }
        if (text == null) {
            // Nowhere to draw it; a toast is the last resort, but it at least says something.
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return
        }
        text.text = getString(R.string.island_load_failed, message)
        if (!isExpanded) isExpanded = true
        applyWindowState()
    }

    /**
     * Collapsed: exactly the rectangle, so every touch outside it reaches the app below.
     *
     * `WRAP_CONTENT` does the work — the prompt box child is `GONE`, so it is not measured
     * and the container's natural size is the rectangle's and nothing else.
     */
    private fun applyCollapsedParams(params: WindowManager.LayoutParams) {
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.START
        // Not focusable, so the app underneath keeps its own input.
        // bubbleParams: Use FLAG_NOT_FOCUSABLE. DO NOT add FLAG_WATCH_OUTSIDE_TOUCH to the bubble!
        params.flags = bubbleFlags(focusable = false)
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
    }

    /**
     * Expanded: the exact rect the page pushed, in physical pixels, and never more.
     *
     * The screen metrics are read on every pass, never hardcoded; the rect comes from the
     * page and is capped at the screen width inside [IslandBridgeCommands.windowBounds], so
     * there is no path here that produces a screen-filling window.
     */
    private fun applyPanelParams(params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        if (islandFailed) {
            // The page never produced a rect, so there is no rect to match: let the error
            // text size itself rather than guessing a panel size.
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
        } else {
            val bounds = IslandBridgeCommands.windowBounds(
                islandWidthCss, islandHeightCss, metrics.density, metrics.widthPixels
            )
            params.width = bounds[0]
            params.height = bounds[1]
        }
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        // expandedParams: Use FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH.
        // On outside touch event, hide webViewContainer.
        params.flags = expandedFlags(focusable = islandFocused)
        params.softInputMode = if (islandFocused) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
    }

    /**
     * Expands to the prompt box, keeping the panel where the rectangle was left.
     *
     * The panel inherits the rectangle's position and is much wider, so the origin is
     * re-clamped by [applyWindowState] rather than trusted.
     */
    private fun expand() {
        if (island == null) {
            Log.i(TAG, "No island to expand into; staying on the rectangle")
            return
        }
        isExpanded = true
        applyWindowState()
        revealPrompt()
    }

    /**
     * Expands to the assistant view, re-attaching the WebView overlay and clearing
     * FLAG_NOT_FOCUSABLE so the soft keyboard can pop up when typing in the chat.
     * This is called from onRootTouch when the collapsed bubble is tapped (not dragged).
     */
    private fun expandToAssistantView() {
        // Directly expand the WebView to full assistant size (360x270dp)
        // Keep the bubble visible 24/7; do NOT hide it
        isExpanded = true
        isExpandedState = true
        isGreetingActive = false

        island?.view?.apply {
            visibility = View.VISIBLE
            alpha = 1f
        }

        val params = layoutParams ?: return
        val metrics = resources.displayMetrics

        // Expand WebView to fixed assistant size at upper center
        params.width = dp(360).coerceAtMost(metrics.widthPixels)
        params.height = dp(270).coerceAtMost(metrics.heightPixels)
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(60) // 60dp from top
        params.x = 0

        // Clear FLAG_NOT_FOCUSABLE so the soft keyboard can pop up when typing in chat
        params.flags = expandedFlags(focusable = true)
        // expandedParams: Use FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH.
        // On outside touch event, hide webViewContainer.
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        clampOnScreen(params)
        pushLayout()
        island?.view?.evaluateJavascript(
            "if (window.CoucouAndroid && window.CoucouAndroid.openChat) { window.CoucouAndroid.openChat(); } else if (window.CoucouAndroid) { window.CoucouAndroid.setCollapsed(false); }",
            null
        )

    }

    /**
     * Shrinks back to the rectangle.
     *
     * Focus and the IME go first: the window has to stop being focusable for the app
     * underneath to get its input back, and it has to stop being focusable *before* the
     * keyboard is dismissed or the IME keeps the window alive.
     */
    private fun collapse() {
        if (!isExpanded && overlayView == null && island?.view?.visibility != View.VISIBLE) return
        revealPending = false
        isExpanded = false
        isExpandedState = false
        isGreetingActive = false
        if (islandFocused) {
            islandFocused = false
            setFocusable(false)
        }

        val wm = windowManager ?: return
        val root = overlayView ?: return
        val params = layoutParams ?: return

        val iv = island?.view
        if (iv != null && iv.visibility == View.VISIBLE) {
            iv.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    iv.visibility = View.GONE
                    iv.alpha = 1f
                    showCollapsedBubble(wm, root, params)
                }
                .start()
        } else {
            showCollapsedBubble(wm, root, params)
        }
    }

    private fun showCollapsedBubble(wm: WindowManager, root: View, params: WindowManager.LayoutParams) {
        applyCollapsedParams(params)
        // Position in the corner
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(16)
        params.y = dp(80)
        bubbleView?.visibility = View.VISIBLE
        bubbleView?.alpha = 1f
        clampOnScreen(params)
        try {
            wm.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update layout for collapsed bubble", e)
        }
        characterView?.setHostVisible(true)
        islandDescription = describeWindow(params)
    }

    /**
     * Creates the island and boots its page with the window parked off screen.
     *
     * A WebView that has never been attached to a window never lays out, so the page would
     * boot with a zero-width viewport, could never measure itself, and would never push the
     * rect the window is sized from. So the window goes up once — off screen, below the
     * bottom edge — and stays there until the page has measured itself. Nothing renders on
     * screen (the page is transparent and the window is out of bounds) but the page is warm
     * by the time the user taps the rectangle.
     *
     * The warm window is deliberately *screen* sized rather than panel sized: the panel size
     * is derived from the rect the page has not pushed yet, so using it here would make the
     * viewport 1 CSS px wide and deadlock the boot.
     */
    private fun warmIsland() {
        if (island == null) inflateIsland()
        val page = island ?: return
        if (islandWarm) return
        val root = ensureContainer() ?: return
        val params = layoutParams ?: buildLayoutParams().also { layoutParams = it }
        val metrics = resources.displayMetrics
        val bounds = IslandBridgeCommands.windowBounds(
            metrics.widthPixels.toDouble(),
            WARMUP_HEIGHT_DP.toDouble(),
            metrics.density,
            metrics.widthPixels
        )
        params.width = bounds[0]
        params.height = bounds[1]
        params.x = 0
        // Below the bottom edge: the page renders and runs, nothing is on screen and no
        // touch can land on it.
        params.y = metrics.heightPixels
        page.view.visibility = View.VISIBLE
        try {
            windowManager?.addView(root, params)
            overlayView = root
        } catch (e: Exception) {
            Log.w(TAG, "Could not warm the island window", e)
            return
        }
        islandWarm = true
        page.resume()
        page.load()
        // Belt and braces: if the page never measures itself the window would wait for a
        // height that never arrives, and a tap would open nothing at all.
        mainHandler.postDelayed({ assumePromptSize() }, ISLAND_BOOT_TIMEOUT_MS)
    }

    /**
     * Boot produced no rect in time, so one is assumed.
     *
     * A wrong height is recoverable — the page pushes a real rect on its next frame and
     * [applyPanelParams] takes over. An un-openable window is not.
     */
    private fun assumePromptSize() {
        if (islandSized || islandFailed) return
        val metrics = resources.displayMetrics
        islandWidthCss = (metrics.widthPixels / metrics.density).toDouble()
        islandHeightCss = FALLBACK_PROMPT_HEIGHT_DP.toDouble()
        islandSized = true
        Log.w(TAG, "Island pushed no rect; assuming a ${FALLBACK_PROMPT_HEIGHT_DP}dp prompt box")
        applyWindowState()
        if (revealPending) {
            revealPending = false
            revealPrompt()
        }
    }

    /**
     * The island in a WebView, or null when it cannot be built.
     *
     * A device with no WebView provider, or a WebView that cannot be constructed, must
     * still show the overlay — so this is a soft failure, not a fatal one, and the
     * rectangle carries on alone.
     */
    private fun inflateIsland(): View? {
        val host = islandBridge ?: return null
        if (island != null) return island!!.view
        val created = CoucouIslandWebView.create(this, host) ?: run {
            Log.w(TAG, "Island WebView unavailable; the rectangle is all the overlay has")
            return null
        }
        created.onReady = { mainHandler.postDelayed({ onIslandReady() }, ISLAND_REVEAL_DELAY_MS) }
        created.onExternalUrl = { url -> mainHandler.post { openExternalUrl(url) } }
        created.onFailed = { message -> islandListener?.onPageFailed(message) }
        island = created
        islandHosting = true
        islandDescription = "island: on loading"
        Log.i(TAG, "Island WebView created; loading ${CoucouIslandWebView.PAGE_URL}")
        return created.view
    }

    /**
     * The page has finished booting.
     *
     * Shown straight onto the prompt view rather than waiting for a tap: the page only
     * measures itself once it is on screen, and the window's height comes from that. Doing
     * it here — while the window is still parked off screen — is what lets the boot finish
     * before the user's first tap.
     */
    private fun onIslandReady() {
        Log.i(TAG, "Island page ready; screen=${resources.displayMetrics.widthPixels}px")
        islandFailed = false
        islandDescription = "island: booted sized=$islandSized"
        if (!isGreetingActive) {
            island?.showPrompt()
            if (islandSized) applyWindowState()
        }
    }

    /**
     * The page pushed a rect, so the prompt box now has a height.
     *
     * The first one also settles the window: it is parked off screen until this arrives, and
     * an expand that was asked for in the meantime is honoured here rather than dropped.
     */
    private fun onIslandSized() {
        val firstTime = !islandSized
        islandSized = true
        if (isGreetingActive) {
            val params = layoutParams ?: return
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.y = 0
            val metrics = resources.displayMetrics
            params.width = resources.getDimensionPixelSize(R.dimen.coucou_island_intro_width).coerceAtMost(metrics.widthPixels)
            params.height = resources.getDimensionPixelSize(R.dimen.coucou_island_intro_height)
            pushLayout()
            return
        }
        if (isExpanded) {
            applyPanelParams(layoutParams ?: return)
            clampOnScreen(layoutParams ?: return)
        }
        if (firstTime) {
            applyWindowState()
            if (revealPending) {
                revealPending = false
                revealPrompt()
            }
        } else {
            pushLayout()
        }
    }

    /**
     * Opens the island on the prompt view and raises the keyboard.
     *
     * The prompt view, not home: the REDO brief hides the home/overview views, so the first
     * thing shown after a tap is the field the user tapped to type into.
     */
    private fun revealPrompt() {
        val page = island ?: return
        if (islandFailed) {
            // There is no page to show and no field to type into; the error box is the
            // window's content now.
            return
        }
        if (!islandSized) {
            // Still booting: there is no height to open at yet.
            revealPending = true
            return
        }
        page.showPrompt()
        setIslandFocus(true)
    }

    /**
     * Gives the window keyboard focus when the chat field asks for it, and takes it back
     * when it does not.
     *
     * This is the part that makes typing work at all: with `FLAG_NOT_FOCUSABLE` the
     * overlay never receives key events, so the page's `<input>` cannot be edited. The
     * flag change has to be re-applied by re-adding the window, and the IME has to be
     * requested after that, or the request is dropped for having no focus.
     */
    private fun setIslandFocus(focused: Boolean) {
        if (islandFocused == focused) return
        islandFocused = focused
        setFocusable(focused)
        Log.i(TAG, "Island keyboard focus -> $focused")
    }

    /**
     * Makes the window focusable and raises the IME, in the only order that works.
     *
     * Each step is a link in one chain and any of them can be skipped silently, which is
     * why the keyboard "never appeared" when they were:
     *  1. clear `FLAG_NOT_FOCUSABLE`, or the window never receives key events at all;
     *  2. clear `FLAG_ALT_FOCUSABLE_IM`, or the IME has to route around this window;
     *  3. push both down with [WindowManager.updateViewLayout];
     *  4. `requestFocus()` on the WebView, so the page's `<input>` is the focused view;
     *  5. `showSoftInput` last — on a view that does not hold focus it is dropped without
     *     a word, which is exactly the "no keyboard" symptom.
     *
     * The wait before step 5 covers the IME's own round trip to the newly focused window.
     */
    private fun setFocusable(focusable: Boolean) {
        val params = layoutParams ?: return
        val view = island?.view
        // expandedParams: Use FLAG_NOT_TOUCH_MODAL or FLAG_WATCH_OUTSIDE_TOUCH.
        params.flags = expandedFlags(focusable = focusable)
        params.softInputMode = if (focusable) {
            // Resize, so the prompt box is pushed up instead of being covered by the keyboard.
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        // 3. Flags are read by the window manager when the layout is applied, so they have
        // to be pushed before anything can react to them.
        pushLayout()
        if (view == null) return
        if (!focusable) {
            hideKeyboard(view)
            return
        }
        view.requestFocus()
        mainHandler.postDelayed({
            if (islandFocused) showKeyboard(view)
        }, KEYBOARD_SHOW_DELAY_MS)
    }

    /** Moves the window by the page's drag delta, which arrives in CSS px (= dp here). */
    private fun dragWindowBy(dx: Double, dy: Double) {
        val params = layoutParams ?: return
        val density = resources.displayMetrics.density
        params.x = (params.x + dx * density).toInt()
        params.y = (params.y + dy * density).toInt()
        clampOnScreen(params)
        pushLayout()
    }

    /**
     * Re-clamps the position against the screen.
     *
     * On every shape change, not only while dragging: the panel is nearly screen-wide and
     * inherits the rectangle's position, so an unclamped origin would leave most of it off
     * screen.
     */
    private fun clampOnScreen(params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        // WRAP_CONTENT reads as a negative number, so anything that is not an explicit size
        // is measured as the rectangle instead.
        val width = if (params.width > 0) params.width else collapsedWidthPx()
        val height = if (params.height > 0) params.height else collapsedHeightPx()
        val at = IslandBridgeCommands.clampToScreen(
            params.x, params.y, width, height, metrics.widthPixels, metrics.heightPixels
        )
        params.x = at[0]
        params.y = at[1]
    }

    /** Applies [layoutParams] to the attached window, tolerating a detached view. */
    private fun pushLayout() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        runCatching { windowManager?.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "Could not move the overlay window", it) }
    }

    /**
     * The rectangle's size in px: what it actually measured, or @Cline's token until the
     * first layout pass has run.
     */
    private fun collapsedWidthPx(): Int =
        bubbleView?.width?.takeIf { it > 0 } ?: bubbleCardPx()

    private fun collapsedHeightPx(): Int =
        bubbleView?.height?.takeIf { it > 0 } ?: bubbleCardPx()

    private fun bubbleCardPx(): Int =
        resources.getDimensionPixelSize(R.dimen.coucou_bubble_card_size)

    /** One line of window state for @AGY's device pass, under `CoucouOverlayService`. */
    private fun describeWindow(params: WindowManager.LayoutParams): String {
        val m = resources.displayMetrics
        return "window ${params.width}x${params.height}px at ${params.x},${params.y} " +
            "screen ${m.widthPixels}x${m.heightPixels}px " +
            "(${(m.widthPixels / m.density).toInt()}x${(m.heightPixels / m.density).toInt()}dp " +
            "@${m.density}) rect ${islandWidthCss}x${islandHeightCss}css " +
            "expanded=$isExpanded focus=$islandFocused drag=$pageDragging"
    }

    /** Opens a URL outside the overlay; `coucou://settings` comes back to this app. */
    private fun openExternalUrl(url: String) {
        val intent = if (url == IslandBridgeHost.SETTINGS_DEEP_LINK) {
            // The desktop build opens a second settings window; on Android that is this
            // app's own screen, which is already behind the overlay.
            packageManager.getLaunchIntentForPackage(packageName)
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent == null) {
            Log.w(TAG, "Nothing on this device can open $url")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Could not open $url", it) }
    }

    /**
     * Takes the window off screen but keeps everything warm.
     *
     * The rectangle and the WebView stay built, so coming back is a re-add rather than a
     * WebView spawn plus a page load.
     */
    private fun removeOverlay() {
        // Window hidden is the other pause trigger; stop the loop before the view detaches.
        characterView?.setHostVisible(false)
        island?.pause()
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
                .onFailure { Log.w(TAG, "Overlay view was already detached", it) }
        }
        overlayView = null
        characterView = null
        characterDescription = ""
        isExpanded = false
        isExpandedState = false
        islandSized = false
        revealPending = false
        pageDragging = false
    }

    /** Drops the warm WebView and the rectangle; the next start builds both again. */
    private fun releaseOverlayParts() {
        releaseIsland()
        bubbleView = null
        container = null
    }

    /**
     * Tears the island down for good. The WebView cannot be reused once destroyed, so the
     * next start builds a fresh one.
     */
    private fun releaseIsland() {
        island?.destroy()
        island = null
        islandFocused = false
        islandSized = false
        islandWarm = false
        islandHosting = false
        islandDescription = "island: off"
        islandWidthCss = 0.0
        islandHeightCss = 0.0
        pageDragging = false
    }

    /**
     * Flags for the collapsed bubble window.
     *
     * The bubble must NOT have FLAG_WATCH_OUTSIDE_TOUCH: outside touches must
     * pass through to the app below, and the bubble must remain visible 24/7.
     * Only FLAG_NOT_FOCUSABLE keeps the IME away until the chat field requests it.
     */
    private fun bubbleFlags(focusable: Boolean = false): Int =
        if (focusable) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        }

    /**
     * Flags for the expanded chat drawer window.
     *
     * FLAG_NOT_TOUCH_MODAL allows the app underneath to receive touches.
     * FLAG_WATCH_OUTSIDE_TOUCH delivers outside-touch events so the drawer
     * can be collapsed while the bubble stays visible.
     * FLAG_ALT_FOCUSABLE_IM lets the IME route through when the chat field is active.
     */
    private fun expandedFlags(focusable: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        flags = if (focusable) {
            (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) and
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        }
        return flags
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            bubbleFlags(focusable = false),
            // TRANSLUCENT is what lets the island be a rounded rectangle with nothing
            // behind it; an opaque window is a solid black slab over whatever is below.
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = 0
        }
    }

    /**
     * Inflates the collapsed bubble.
     *
     * This is the *only* native shape now: the REDO brief replaces the ask bar with the
     * prompt box in the WebView, so the rectangle is both the resting state and the
     * fallback for a device that cannot host the island. A missing layout falls back to a
     * minimal programmatic bubble so the overlay still shows something.
     */
    @SuppressLint("InflateParams")
    private fun inflateBubble(): View {
        val view = inflateLayoutByName(LAYOUT_COLLAPSED)
        if (view != null) {
            bindBubble(view)
            return view
        }
        return buildFallbackView()
    }

    @SuppressLint("InflateParams")
    private fun inflateLayoutByName(name: String): View? {
        val resId = resources.getIdentifier(name, "layout", packageName)
        if (resId == 0) {
            Log.w(TAG, "Layout '$name' not found, using fallback view")
            return null
        }
        return try {
            val themedContext = ContextThemeWrapper(this, R.style.Theme_Coucou)
            LayoutInflater.from(themedContext).inflate(resId, null, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate layout '$name'", e)
            null
        }
    }

    /**
     * Wires up whatever ids the inflated bubble happens to provide. Every id is optional
     * so the service is resilient to layout revisions on @Cline's side.
     */
    private fun bindBubble(view: View) {
        val root = findChild<View>(view, "bubble_root") ?: view
        root.setOnTouchListener { _, event -> onRootTouch(event) }

        bindCharacter(view)

        findChild<View>(view, "bubble_mic")?.setOnClickListener {
            // Voice input is a later sprint; acknowledge rather than fail silently.
            Toast.makeText(this, R.string.mic_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    // region character

    /**
     * Finds @Cline's [CoucouCharacterView] in the inflated layout, if it hosts one.
     *
     * The view is optional in both layouts: without it the overlay still works and only the
     * animation is missing, so a layout that has not landed yet degrades instead of
     * crashing. Character-originated sounds are forwarded to [playSound] here, which means
     * the engine itself never needs a [SoundPlayer] and stays unit-testable.
     */
    private fun bindCharacter(view: View) {
        val character = CHARACTER_IDS.firstNotNullOfOrNull { name ->
            findChild<CoucouCharacterView>(view, name)
        }
        characterView = character
        character?.onEvent = { event ->
            when (event) {
                is CoucouCharacterEngine.CharacterEvent.Sound -> playSound(event.sound)
                CoucouCharacterEngine.CharacterEvent.Dizzy ->
                    setCharacterState(CoucouState.DIZZY)
            }
        }
        if (character != null) {
            // A freshly bound view inherits the current screen state, so the bubble never
            // spins behind a locked screen.
            character.setScreenOn(isScreenOn)
            character.setHostVisible(overlayView != null)
            Log.i(
                TAG,
                "Character view bound; state=${character.characterState.wireName} " +
                    "screenOn=$isScreenOn"
            )
        } else {
            Log.w(TAG, "No CoucouCharacterView in layout; running without the animation")
        }
    }

    /**
     * Moves the character to [state] and records it for QA.
     *
     * @param sound played alongside the transition, if any. Sound assets may not have
     *   landed yet — [playSound] no-ops in that case.
     */
    private fun setCharacterState(state: CoucouState, sound: SoundPlayer.Sound? = null) {
        characterStateName = state.wireName
        sound?.let { playSound(it) }
        val character = characterView ?: return
        character.setState(state)
        Log.i(TAG, "Character state -> ${state.wireName}")
        if (state in TRANSIENT_STATES) scheduleCharacterReset(character)
    }

    /** Returns the character to a resting state once a transient one has played out. */
    private fun scheduleCharacterReset(character: CoucouCharacterView) {
        mainHandler.postDelayed({
            runCatching {
                if (characterView === character) {
                    setCharacterState(if (isExpanded) CoucouState.QUESTION else CoucouState.IDLE)
                }
            }
        }, CHARACTER_RESET_MS)
    }

    /**
     * Pauses/resumes the character with the screen (report §2.5).
     *
     * `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` cannot be declared in the manifest, so the
     * receiver is registered here for as long as the service lives.
     */
    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> setScreenState(false)
                    Intent.ACTION_SCREEN_ON -> setScreenState(true)
                    Intent.ACTION_CONFIGURATION_CHANGED -> {
                        // Screen rotated or configuration changed: the page has to re-measure
                        // and the window has to be re-clamped against the new screen.
                        island?.postScreenChanged()
                        layoutParams?.let { clampOnScreen(it) }
                        pushLayout()
                    }
                }
            }
        }
        screenReceiver = receiver
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    receiver,
                    filter,
                    // System broadcasts only; NOT_EXPORTED keeps other apps out and still
                    // delivers SCREEN_ON/OFF and CONFIGURATION_CHANGED.
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            screenReceiverRegistered = true
        } catch (e: Exception) {
            // Losing the receiver only costs us the pause optimisation, never correctness:
            // the character still animates, because `animate` defaults to true.
            Log.w(TAG, "Could not register screen on/off receiver", e)
        }
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        screenReceiverRegistered = false
        val receiver = screenReceiver
        screenReceiver = null
        if (receiver != null) runCatching { unregisterReceiver(receiver) }
    }

    private fun setScreenState(on: Boolean) {
        if (isScreenOn == on) return
        isScreenOn = on
        screenOn = on
        characterView?.setScreenOn(on)
        characterView?.let { characterDescription = CoucouCharacterView.describeState(it) }
        // Same rule for the island: no rAF and no timers behind a locked screen.
        island?.let { if (on) it.resume() else it.pause() }
        Log.i(TAG, "Screen ${if (on) "on" else "off"}; animation paused=${!on}")
    }

    // endregion

    /**
     * Name kept distinct from [View.findViewById] so the generic bound is unambiguous.
     *
     * Accepts both a bare id (`ask_input`) and a `coucou_`-prefixed one
     * (`coucou_ask_input`), so this binds regardless of which naming convention the
     * layout author settled on. Returns null when neither exists, which keeps the
     * service working against partially-landed layouts.
     */
    private fun <T : View> findChild(root: View, idName: String): T? {
        val candidates = listOf(idName, ID_PREFIX + idName)
        for (candidate in candidates) {
            val id = resources.getIdentifier(candidate, "id", packageName)
            if (id != 0) {
                val view = root.findViewById<T>(id)
                if (view != null) {
                    return view
                }
            }
        }
        return null
    }

    /** Minimal programmatic bubble, used only if the layout resources are absent. */
    private fun buildFallbackView(): View {
        // Service contexts do not inherit the application theme, so theme attrs do not
        // resolve here either. Same wrap as inflateLayoutByName, otherwise the fallback
        // renders with unthemed defaults.
        val context = ContextThemeWrapper(this, R.style.Theme_Coucou)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor("#141518"))
            }
        }
        container.addView(TextView(context).apply {
            text = getString(R.string.app_name)
        })
        // The fallback path builds its own view tree, so it hosts the character itself;
        // the layouts owned by @Cline carry their own instance.
        val fallbackCharacter = CoucouCharacterView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
            id = idFor("character")
        }
        container.addView(fallbackCharacter, 0)
        characterView = fallbackCharacter
        fallbackCharacter.onEvent = { event ->
            when (event) {
                is CoucouCharacterEngine.CharacterEvent.Sound -> playSound(event.sound)
                CoucouCharacterEngine.CharacterEvent.Dizzy -> setCharacterState(CoucouState.DIZZY)
            }
        }
        container.setOnTouchListener { _, event -> onRootTouch(event) }
        return container
    }

    private fun idFor(name: String): Int = resources.getIdentifier(name, "id", packageName)

    // endregion

    // region drag + tap

    /**
     * Drag and tap on the collapsed rectangle.
     *
     * A movement past the platform's [touchSlop] is a drag and anything shorter is a tap,
     * which expands. The threshold is the system's rather than one tuned here, so the
     * gesture matches every other app on the device.
     *
     * Clamping uses the rectangle's own size, so it can be dragged anywhere the whole
     * rectangle fits — including the very edges of the screen.
     */
    private fun onRootTouch(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        val metrics = resources.displayMetrics
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downTouchX = event.x
                downTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging &&
                    (kotlin.math.abs(event.x - downTouchX) > touchSlop ||
                        kotlin.math.abs(event.y - downTouchY) > touchSlop)
                ) {
                    isDragging = true
                }
                if (isDragging) {
                    params.x = (params.x + (event.rawX - downRawX)).toInt()
                    params.y = (params.y + (event.rawY - downRawY)).toInt()
                    val at = IslandBridgeCommands.clampToScreen(
                        params.x, params.y,
                        resources.getDimensionPixelSize(R.dimen.coucou_bubble_card_size),
                        resources.getDimensionPixelSize(R.dimen.coucou_bubble_card_size),
                        metrics.widthPixels, metrics.heightPixels
                    )
                    params.x = at[0]
                    params.y = at[1]
                    pushLayout()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    islandDescription = describeWindow(params)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging && Math.hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble()) < touchSlop) {
                    bubbleView?.performClick()
                    characterView?.performClick()
                    characterView?.onTap()
                    expandToAssistantView()
                } else {
                    // Smooth snap to screen edge on release
                    val cardSize = resources.getDimensionPixelSize(R.dimen.coucou_bubble_card_size)
                    val midX = params.x + cardSize / 2
                    val targetX = if (midX < metrics.widthPixels / 2) {
                        dp(16) // Snap to left edge with padding
                    } else {
                        metrics.widthPixels - cardSize - dp(16) // Snap to right edge with padding
                    }
                    params.x = targetX
                    pushLayout()
                    islandDescription = describeWindow(params)
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    // endregion

    // region command routing

    /** Routes [text] through the router and surfaces the outcome to the user. */
    private fun submitCommand(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return
        }
        val command = CommandRouter.parse(trimmed)
        // The character reacts before the router runs: a launch is fast, but the thinking
        // pose is what makes the bubble feel alive while PackageManager does its lookup.
        setCharacterState(CoucouState.THINKING)
        val result = router?.route(command)
        val message = when (result) {
            is CommandResult.Success -> {
                // Collapse back to the rectangle after a successful launch.
                mainHandler.postDelayed({ collapse() }, COLLAPSE_DELAY_MS)
                setCharacterState(CoucouState.FINISHED, SoundPlayer.Sound.SEND)
                getString(R.string.command_opened, result.message ?: trimmed)
            }
            is CommandResult.Failed -> {
                setCharacterState(CoucouState.ERROR, SoundPlayer.Sound.ERROR)
                getString(R.string.command_failed, result.reason)
            }
            is CommandResult.Unknown -> {
                setCharacterState(CoucouState.ERROR, SoundPlayer.Sound.ERROR)
                getString(R.string.command_not_found, trimmed)
            }
            null -> {
                setCharacterState(CoucouState.ERROR, SoundPlayer.Sound.ERROR)
                getString(R.string.command_not_found, trimmed)
            }
        }
        Log.i(TAG, "Command '$trimmed' -> $result")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Puts [message] in the clipboard; small convenience for verifying results. */
    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun showKeyboard(target: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun releaseSounds() {
        soundPlayer?.release()
        soundPlayer = null
    }

    /** Plays [sound] when assets are available; silently no-ops otherwise. */
    private fun playSound(sound: SoundPlayer.Sound) {
        soundPlayer?.play(sound)
    }

    /**
     * Dismisses the IME for [target].
     *
     * Takes the view rather than reaching for the WebView itself, so the token is read from
     * whichever window the view is actually attached to right now.
     */
    private fun hideKeyboard(target: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    /** Dismisses the IME if the island is up; a no-op when there is no text field. */
    private fun hideKeyboard() {
        island?.view?.let { hideKeyboard(it) }
    }

    // endregion

    private fun stopOverlayService() {
        hideKeyboard()
        removeOverlay()
        releaseOverlayParts()
        releaseSounds()
        unregisterScreenReceiver()
        hasGreeted = false
        characterStateName = CoucouState.IDLE.wireName
        characterDescription = ""
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.notification_channel_name)
            val descriptionText = getString(R.string.notification_channel_desc)
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Always tear the window down, otherwise a killed service leaves the bubble
        // stuck on screen with no way to interact with it.
        hideKeyboard()
        removeOverlay()
        releaseOverlayParts()
        releaseSounds()
        unregisterScreenReceiver()
        isRunning = false
        screenOn = true
        isScreenOn = true
        super.onDestroy()
    }

    }