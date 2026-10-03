package com.coucou.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns the floating bubble.
 *
 * The bubble is a [WindowManager] overlay of type [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * so it can draw above other apps. Responsibilities:
 *  - keep itself alive as a foreground service (survives the home screen)
 *  - add/update/remove the overlay view
 *  - drag the bubble, and treat a tap (vs. a drag) as "expand"
 *  - swap between the collapsed bubble and the expanded ask bar
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

    private var isExpanded = false
    private var isDragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTouchX = 0f
    private var downTouchY = 0f
    private val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

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

        /** Layout names owned by @Cline, resolved reflectively so this compiles standalone. */
        const val LAYOUT_COLLAPSED = "overlay_bubble"
        const val LAYOUT_EXPANDED = "overlay_ask_bar"

        private const val ID_PREFIX = "coucou_"
        private const val COLLAPSE_DELAY_MS = 400L
        private const val RESULT_TIMEOUT_MS = 2500L

        var isRunning: Boolean = false
            private set

        /** True while the ask bar is showing, for diagnostics and the pass checklist. */
        var isExpandedState: Boolean = false
            private set

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
            if (isExpanded == expanded) {
                return
            }
            wm.removeView(current)
            overlayView = null
        }
        val view = inflateBubble(expanded)
        val params = layoutParams ?: buildLayoutParams().also { layoutParams = it }
        applyFlagsForState(params, expanded)
        try {
            wm.addView(view, params)
            overlayView = view
            isExpanded = expanded
            isExpandedState = expanded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            overlayView = null
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // View may already be detached; nothing to do.
        }
        overlayView = null
        isExpanded = false
        isExpandedState = false
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
     * Window flags must change with state: the expanded ask bar hosts an EditText, which
     * cannot receive text or show the IME while [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE]
     * is set. The collapsed bubble does want NOT_FOCUSABLE so it never steals input from
     * the app underneath.
     */
    private fun applyFlagsForState(params: WindowManager.LayoutParams, expanded: Boolean) {
        if (expanded) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            // Soft input must be able to resize/pan the window while typing.
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
    }

    /**
     * Inflates the collapsed bubble or the expanded ask bar.
     *
     * @Cline owns `res/layout`; both layouts are looked up by name so this compiles and
     * runs whether or not they have landed. If a layout is missing, a minimal
     * programmatic fallback keeps the bubble usable instead of crashing.
     */
    @SuppressLint("InflateParams")
    private fun inflateBubble(expanded: Boolean): View {
        val name = if (expanded) LAYOUT_EXPANDED else LAYOUT_COLLAPSED
        val view = inflateLayoutByName(name)
        if (view != null) {
            bindBubble(view, expanded)
            return view
        }
        return buildFallbackView(expanded)
    }

    @SuppressLint("InflateParams")
    private fun inflateLayoutByName(name: String): View? {
        val resId = resources.getIdentifier(name, "layout", packageName)
        if (resId == 0) {
            Log.w(TAG, "Layout '$name' not found, using fallback view")
            return null
        }
        return try {
            LayoutInflater.from(this).inflate(resId, null, false)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate layout '$name'", e)
            null
        }
    }

    /**
     * Wires up whatever ids the inflated layout happens to provide. Every id is
     * optional so the service is resilient to layout revisions on @Cline's side.
     */
    private fun bindBubble(view: View, expanded: Boolean) {
        val root = findChild<View>(view, "bubble_root") ?: view
        root.setOnTouchListener { _, event -> onRootTouch(event) }

        findChild<EditText>(view, "ask_input")?.let { input ->
            if (expanded) {
                input.requestFocus()
                showKeyboard(input)
                input.setOnEditorActionListener { _, _, _ ->
                    val text = input.text?.toString().orEmpty()
                    input.setText("")
                    submitCommand(text)
                    true
                }
            }
        }

        findChild<ImageButton>(view, "ask_close")?.setOnClickListener {
            showOverlay(expanded = false)
        }

        findChild<View>(view, "bubble_mic")?.setOnClickListener {
            // Voice input is a later sprint; acknowledge rather than fail silently.
            Toast.makeText(this, R.string.mic_not_available, Toast.LENGTH_SHORT).show()
        }

        findChild<View>(view, "ask_result")?.let { result ->
            result.visibility = View.GONE
        }
    }

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
    private fun buildFallbackView(expanded: Boolean): View {
        val context = this
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(ContextCompat.getColor(context, android.R.color.background_light))
            }
        }
        if (expanded) {
            container.addView(TextView(context).apply {
                text = getString(R.string.ask_bar_hint)
            })
            container.addView(EditText(context).apply {
                hint = getString(R.string.search_hint)
                id = idFor("ask_input")
            })
        } else {
            container.addView(TextView(context).apply {
                text = getString(R.string.app_name)
            })
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
                    // Keep the bubble on-screen.
                    val maxX = resources.displayMetrics.widthPixels - dp(48)
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
                    // A tap (not a drag) toggles the ask bar.
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
        val result = router?.route(command)
        val message = when (result) {
            is CommandResult.Success -> {
                // Collapse back to the bubble after a successful launch.
                mainHandler.postDelayed({ showOverlay(expanded = false) }, COLLAPSE_DELAY_MS)
                getString(R.string.command_opened, result.message ?: trimmed)
            }
            is CommandResult.Failed -> getString(R.string.command_failed, result.reason)
            is CommandResult.Unknown -> getString(R.string.command_not_found, trimmed)
            null -> getString(R.string.command_not_found, trimmed)
        }
        Log.i(TAG, "Command '$trimmed' -> $result")
        showResult(message)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Puts [message] in the clipboard; small convenience for verifying results. */
    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun showResult(message: String) {
        val view = overlayView ?: return
        val target = findChild<TextView>(view, "ask_result")
        if (target != null) {
            target.text = message
            target.visibility = View.VISIBLE
            mainHandler.postDelayed({
                runCatching {
                    if (overlayView === view) {
                        target.visibility = View.GONE
                    }
                }
            }, RESULT_TIMEOUT_MS)
        }
    }

    private fun showKeyboard(input: EditText) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        overlayView?.let { view ->
            findChild<EditText>(view, "ask_input")?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        }
    }

    // endregion

    private fun stopOverlayService() {
        hideKeyboard()
        removeOverlay()
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
        isRunning = false
        super.onDestroy()
    }

    }