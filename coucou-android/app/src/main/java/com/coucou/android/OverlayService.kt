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
     * Sprint 3 (Plan A): the upstream desktop island running in a WebView, and the bridge
     * that stands in for Tauri. Null when the WebView could not be created, in which case
     * the native bubble below is used instead.
     */
    private var island: CoucouIslandWebView? = null
    private var islandBridge: IslandBridgeHost? = null

    /** True while the island is in charge of the window, so expand/collapse comes from the page. */
    private var islandActive = false

    /** View the window was last asked to show; applied once the page reports ready. */
    private var islandTarget = CoucouIslandWebView.VIEW_HOME

    /** Island geometry pushed by the page (CSS px), applied to [layoutParams] on the main thread. */
    private var islandWidthCss = IslandBridgeCommands.STAGE_WIDTH_CSS.toDouble()
    private var islandHeightCss = 0.0
    private var islandCollapsed = false

    /** Whether the window may take keyboard focus right now; mirrors the chat field. */
    private var islandFocused = false

    private var isExpanded = false
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

        /**
         * Grace period between the page reporting ready and revealing the island, so the
         * reveal lands after the boot sequence has finished wiring its listeners.
         */
        private const val ISLAND_REVEAL_DELAY_MS = 300L

        /** How long a transient character state (thinking/finished/error) stays on screen. */
        private const val CHARACTER_RESET_MS = 1600L

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
        router = DefaultCommandRouter(listOf(LaunchAppCommandRouter(appLauncher!!)))
        soundPlayer = SoundPlayer(this).also { it.preloadAvailable() }
        islandBridge = IslandBridgeHost(this, IslandListener())
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
            islandWidthCss = widthCss
            islandHeightCss = heightCss
            // A non-zero height means the island is on screen, so it has left the
            // collapsed wake strip.
            if (heightCss > 0 && islandCollapsed) {
                islandCollapsed = false
            }
            mainHandler.post { applyIslandGeometry() }
        }

        override fun onIslandCollapsed(collapsed: Boolean) {
            islandCollapsed = collapsed
            mainHandler.post { applyIslandGeometry() }
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
                is CommandResult.Success -> getString(R.string.command_opened, result.message ?: query)
                is CommandResult.Failed -> getString(R.string.command_failed, result.reason)
                else -> getString(R.string.command_not_found, query)
            }
            setCharacterState(
                if (result is CommandResult.Success) CoucouState.FINISHED else CoucouState.ERROR,
                if (result is CommandResult.Success) SoundPlayer.Sound.SEND else SoundPlayer.Sound.ERROR
            )
            Log.i(TAG, "Island command '$query' -> $result")
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
                startAsForeground()
                showOverlay(expanded = true)
                return START_STICKY
            }
            ACTION_COLLAPSE -> {
                showOverlay(expanded = false)
                return START_STICKY
            }
            ACTION_COMMAND -> {
                startAsForeground()
                val text = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                if (text.isNotBlank()) {
                    submitCommand(text)
                }
                return START_STICKY
            }
            ACTION_START, null -> {
                startAsForeground()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
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
        // Only add the overlay once we are actually foreground, otherwise the
        // window is rejected on Android 10+ (background window restrictions).
        showOverlay(expanded = isExpanded)
    }

    // region overlay window

    private fun showOverlay(expanded: Boolean) {
        val wm = windowManager ?: return
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            return
        }
        val current = overlayView
        if (current != null) {
            if (islandActive) {
                // The page owns its own collapsed/expanded cycle; a repeated request with
                // the same intent is still handled, because ACTION_EXPAND from the
                // notification means "show the chat", not "re-add the window".
                if (isExpanded == expanded) {
                    islandTarget = viewFor(expanded)
                    island?.reveal(islandTarget)
                    return
                }
            } else if (isExpanded == expanded) {
                return
            }
            wm.removeView(current)
            overlayView = null
        }
        val view = inflateIsland() ?: inflateBubble()
        val params = layoutParams ?: buildLayoutParams().also { layoutParams = it }
        if (islandActive) {
            islandTarget = viewFor(expanded)
            applyIslandParams(params)
        } else {
            applyBubbleParams(params)
        }
        try {
            wm.addView(view, params)
            overlayView = view
            isExpanded = expanded
            isExpandedState = expanded
            // Window is up: the character may run again even if the screen had been off.
            characterView?.setHostVisible(true)
            characterView?.let { characterDescription = CoucouCharacterView.describeState(it) }
            if (islandActive) {
                island?.resume()
                island?.load()
            } else {
                val firstShow = characterStateName == CoucouState.IDLE.wireName && !hasGreeted
                // No-op while the sound assets are still on hold.
                playSound(SoundPlayer.Sound.POP)
                if (firstShow) {
                    hasGreeted = true
                    // The "coucou" wave: greet on first appearance.
                    characterView?.greet()
                }
                setCharacterState(CoucouState.IDLE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            overlayView = null
        }
    }

    /**
     * The island in a WebView, or null to let the caller fall back to the native bubble.
     *
     * A device with no WebView provider, or a WebView that cannot be constructed, must
     * still show the overlay — so this is a soft failure, not a fatal one.
     */
    private fun inflateIsland(): View? {
        val host = islandBridge ?: return null
        if (island != null) return island!!.view
        val created = CoucouIslandWebView.create(this, host) ?: run {
            Log.w(TAG, "Island WebView unavailable; falling back to the native bubble")
            return null
        }
        created.onReady = { mainHandler.postDelayed({ revealIsland() }, ISLAND_REVEAL_DELAY_MS) }
        created.onExternalUrl = { url -> mainHandler.post { openExternalUrl(url) } }
        island = created
        islandActive = true
        islandHosting = true
        islandDescription = "island: on loading"
        Log.i(TAG, "Island WebView created; loading ${CoucouIslandWebView.PAGE_URL}")
        return created.view
    }

    /**
     * Opens the island on [islandTarget] once the page has booted.
     *
     * The desktop build is woken by a `tray` event; with no host events arriving the
     * island would play its greeting and then retract, so the same event is emitted here.
     */
    private fun revealIsland() {
        val page = island ?: return
        if (overlayView == null) return
        page.reveal(islandTarget)
        setCharacterState(CoucouState.IDLE)
    }

    /** The island view "expanded" means now that the native ask bar is gone: the chat. */
    private fun viewFor(expanded: Boolean): String =
        if (expanded) CoucouIslandWebView.VIEW_CHAT else CoucouIslandWebView.VIEW_HOME

    /** Sizes and positions the window around the rect the page pushed. */
    private fun applyIslandGeometry() {
        val params = layoutParams ?: return
        val view = overlayView ?: return
        if (!islandActive) return
        val metrics = resources.displayMetrics
        val bounds = if (islandCollapsed) {
            IslandBridgeCommands.collapsedWindowBounds(metrics.density, metrics.widthPixels)
        } else {
            IslandBridgeCommands.windowBounds(islandHeightCss, metrics.density, metrics.widthPixels)
        }
        params.width = bounds[0]
        params.height = bounds[1]
        params.x = bounds[2]
        islandHosting = true
        islandDescription = "island: on rect=${islandWidthCss}x${islandHeightCss}css " +
            "window=${bounds[0]}x${bounds[1]}px x=${bounds[2]} " +
            "collapsed=$islandCollapsed focus=$islandFocused"
        runCatching { windowManager?.updateViewLayout(view, params) }
            .onFailure { Log.w(TAG, "Could not resize the island window", it) }
    }

    private fun applyIslandParams(params: WindowManager.LayoutParams) {
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.START
        if (params.y == 0 && params.x == 0) {
            params.y = dp(120)
        }
        // The WebView measures itself, so the window starts on its natural size and the
        // page's first rect settles it.
        params.flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        islandFocused = false
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
        val params = layoutParams ?: return
        val view = overlayView ?: return
        if (!islandActive) return
        if (islandFocused == focused) return
        islandFocused = focused
        val flags = params.flags
        params.flags = if (focused) {
            (flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        } else {
            flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        params.softInputMode = if (focused) {
            // Resize, so the island is pushed up instead of being covered by the keyboard.
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        }
        val windowManager = windowManager
        val webView = island?.view
        if (windowManager != null && webView != null) {
            runCatching {
                windowManager.removeView(view)
                windowManager.addView(view, params)
            }.onFailure {
                Log.w(TAG, "Could not switch window focus for the island", it)
                return
            }
            if (focused) {
                webView.requestFocus()
                mainHandler.postDelayed({
                    if (islandFocused && overlayView === view) {
                        showKeyboard(webView)
                    }
                }, KEYBOARD_SHOW_DELAY_MS)
            } else {
                hideKeyboard()
            }
        }
        Log.i(TAG, "Island keyboard focus -> $focused")
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

    private fun removeOverlay() {
        val view = overlayView ?: return
        // Window hidden is the other pause trigger; stop the loop before the view detaches.
        characterView?.setHostVisible(false)
        island?.pause()
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // View may already be detached; nothing to do.
        }
        overlayView = null
        characterView = null
        characterDescription = ""
        isExpanded = false
        isExpandedState = false
    }

    /**
     * Tears the island down for good. The WebView cannot be reused once destroyed, so
     * the next start builds a fresh one.
     */
    private fun releaseIsland() {
        island?.destroy()
        island = null
        islandActive = false
        islandFocused = false
        islandCollapsed = false
        islandHosting = false
        islandDescription = "island: off"
        islandWidthCss = IslandBridgeCommands.STAGE_WIDTH_CSS.toDouble()
        islandHeightCss = 0.0
        islandTarget = CoucouIslandWebView.VIEW_HOME
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
    }

    /**
     * Window geometry and flags for the native bubble.
     *
     * Sprint 3 replaced the native ask bar with the desktop island in a WebView, so
     * there is no longer a second native state to size: the bubble is always
     * WRAP_CONTENT (so it does not swallow touches across the whole screen row, @AGY's
     * Sprint 1 finding) and always NOT_FOCUSABLE (so it never steals input from the app
     * underneath). Anything that wants a text field now goes through the island's chat
     * view and [setIslandFocus].
     */
    private fun applyBubbleParams(params: WindowManager.LayoutParams) {
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
    }

    /**
     * Inflates the collapsed bubble.
     *
     * This is only the fallback for a device that cannot host the island WebView at
     * all; the ask bar that used to inflate here is gone with the rest of the native
     * expanded state. A missing layout falls back to a minimal programmatic bubble so
     * the overlay still shows something.
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
                        // Screen rotated or configuration changed: notify island to update layout
                        if (islandActive) {
                            island?.postScreenChanged()
                        }
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
        if (islandActive) {
            if (on) island?.resume() else island?.pause()
        }
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
                // Resolve the themed surface rather than a fixed light color, so the
                // fallback bubble honours dark mode and dynamic color.
                setColor(
                    MaterialColors.getColor(
                        context,
                        com.google.android.material.R.attr.colorSurface,
                        ContextCompat.getColor(context, android.R.color.background_light)
                    )
                )
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

    private fun onRootTouch(event: MotionEvent): Boolean {
        val params = layoutParams ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                downTouchX = event.x
                downTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging &&
                    (kotlin.math.abs(event.x - downTouchX) > touchSlop ||
                        kotlin.math.abs(event.y - downTouchY) > touchSlop)
                ) {
                    isDragging = true
                }
                if (isDragging) {
                    params.x = (params.x + dx).toInt()
                    params.y = (params.y + dy).toInt()
                    // Keep the window on-screen. The island's panel spans the screen less
                    // its margins, so it is pinned to that margin and only y is free;
                    // clamping x against a narrow bubble would be wrong there.
                    val maxX = if (isExpanded) {
                        dp(16)
                    } else {
                        resources.displayMetrics.widthPixels - dp(48)
                    }
                    val maxY = resources.displayMetrics.heightPixels - dp(48)
                    params.x = params.x.coerceIn(0, maxOf(0, maxX))
                    params.y = params.y.coerceIn(0, maxOf(0, maxY))
                    overlayView?.let { view ->
                        runCatching { windowManager?.updateViewLayout(view, params) }
                    }
                    downRawX = event.rawX
                    downRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    // A tap (not a drag) wakes the island up onto its chat view.
                    characterView?.onTap()
                    playSound(SoundPlayer.Sound.BLIP)
                    showOverlay(expanded = !isExpanded)
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
                // Collapse back to the bubble after a successful launch.
                mainHandler.postDelayed({ showOverlay(expanded = false) }, COLLAPSE_DELAY_MS)
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

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        // Only the island has a text field now; the native bubble is character-only.
        val target: View = island?.view ?: return
        imm.hideSoftInputFromWindow(target.windowToken, 0)
    }

    // endregion

    private fun stopOverlayService() {
        hideKeyboard()
        removeOverlay()
        releaseIsland()
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
        releaseIsland()
        releaseSounds()
        unregisterScreenReceiver()
        isRunning = false
        screenOn = true
        isScreenOn = true
        super.onDestroy()
    }

    }