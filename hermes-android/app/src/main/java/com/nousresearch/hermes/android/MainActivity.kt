package com.nousresearch.hermes.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowInsetsController
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import java.io.IOException
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var gatewayOverlay: LinearLayout
    private lateinit var gatewayStatusText: TextView
    private lateinit var gatewayUrlInput: EditText
    private lateinit var btnRetry: Button
    private lateinit var btnContinue: Button

    private lateinit var nativeBridge: HermesNativeBridge
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private val logRingBuffer = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val maxLogEntries = 100
    private var logFile: java.io.File? = null

    fun appendDebugLog(level: String, tag: String, message: String, source: String = "") {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(Date())
        val entry = if (source.isNotBlank()) "[$time] [$level/$tag] $message $source" else "[$time] [$level/$tag] $message"
        logRingBuffer.addLast(entry)
        while (logRingBuffer.size > maxLogEntries) {
            logRingBuffer.pollFirst()
        }
        when (level) {
            "ERROR" -> Log.e(tag, if (source.isNotBlank()) "$message $source" else message)
            "WARN" -> Log.w(tag, if (source.isNotBlank()) "$message $source" else message)
            else -> Log.i(tag, if (source.isNotBlank()) "$message $source" else message)
        }
        backgroundExecutor.execute {
            try {
                logFile?.let { file ->
                    synchronized(file) {
                        file.appendText("$entry\n")
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback == null) return@registerForActivityResult

        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val clipData = result.data?.clipData
            val dataUri = result.data?.data
            if (clipData != null) {
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else if (dataUri != null) {
                arrayOf(dataUri)
            } else {
                null
            }
        } else {
            null
        }

        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Global uncaught exception handler renders diagnostics instead of crashing to home screen
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                runOnUiThread {
                    showCrashScreen(throwable, "Uncaught Exception in thread '${thread.name}'")
                }
            } catch (_: Throwable) {
                prevHandler?.uncaughtException(thread, throwable)
            }
        }

        try {
            logFile = java.io.File(getExternalFilesDir(null), "hermes_debug.log")
            appendDebugLog("INFO", "HermesWS", "App starting, debug log initialized at ${logFile?.absolutePath}")

            configureEdgeToEdgeDark()
            setContentView(R.layout.activity_main)

            webView = findViewById(R.id.webView)
            progressBar = findViewById(R.id.progressBar)
            gatewayOverlay = findViewById(R.id.gatewayOverlay)
            gatewayStatusText = findViewById(R.id.gatewayStatusText)
            gatewayUrlInput = findViewById(R.id.gatewayUrlInput)
            btnRetry = findViewById(R.id.btnRetry)
            btnContinue = findViewById(R.id.btnContinue)

            findViewById<Button>(R.id.btnDebugLogs).setOnClickListener {
                showDebugLogDialog()
            }
            findViewById<View>(R.id.touchDebugTopLeft).setOnLongClickListener {
                showDebugLogDialog()
                true
            }

            setupWebView()
            setupOverlayActions()
            setupBackNavigation()

            // Safe async gateway health check
            checkGatewayAndLoad()
        } catch (t: Throwable) {
            showCrashScreen(t, "Fatal Startup Error in MainActivity.onCreate")
        }
    }

    private fun showCrashScreen(throwable: Throwable, title: String) {
        try {
            val scrollView = ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0A0A0A"))
                setPadding(32, 48, 32, 48)
            }
            val textView = TextView(this).apply {
                setTextColor(Color.parseColor("#FF5555"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                text = buildString {
                    append("=========================================\n")
                    append("   HERMES ANDROID DIAGNOSTIC REPORT\n")
                    append("=========================================\n\n")
                    append("Title: $title\n")
                    append("Time: ${Date()}\n")
                    append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})\n")
                    append("Package: $packageName\n")
                    append("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
                    append("Git Commit: ${BuildConfig.GIT_COMMIT}\n\n")
                    append("EXCEPTION:\n")
                    append("${throwable::class.java.name}: ${throwable.message}\n\n")
                    append("STACK TRACE:\n")
                    append(Log.getStackTraceString(throwable))
                }
            }
            scrollView.addView(textView)
            setContentView(scrollView)
        } catch (fatal: Throwable) {
            Log.e("HermesAndroid", "Failed to render crash screen", fatal)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::gatewayOverlay.isInitialized && gatewayOverlay.visibility == View.VISIBLE) {
                    gatewayOverlay.visibility = View.GONE
                    return
                }
                if (::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun configureEdgeToEdgeDark() {
        try {
            window.statusBarColor = Color.parseColor("#0A0A0A")
            window.navigationBarColor = Color.parseColor("#0A0A0A")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } catch (_: Throwable) {
            // Window insets / status bar styling failure should never crash the app
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        nativeBridge = HermesNativeBridge(this, webView)

        webView.setBackgroundColor(Color.parseColor("#0A0A0A"))
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        webView.addJavascriptInterface(nativeBridge, HermesNativeBridge.BRIDGE_NAME)

        webView.webViewClient = HermesWebViewClient(this, progressBar)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    fileChooserParams?.acceptTypes?.let { types ->
                        val validTypes = types.filter { it.isNotBlank() }
                        if (validTypes.isNotEmpty()) {
                            putExtra(Intent.EXTRA_MIME_TYPES, validTypes.toTypedArray())
                        }
                    }
                    putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    )
                }

                return try {
                    filePickerLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    false
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val level = when (it.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                        ConsoleMessage.MessageLevel.WARNING -> "WARN"
                        ConsoleMessage.MessageLevel.LOG -> "INFO"
                        ConsoleMessage.MessageLevel.DEBUG -> "DEBUG"
                        ConsoleMessage.MessageLevel.TIP -> "TIP"
                        else -> "LOG"
                    }
                    val msg = it.message() ?: ""
                    val tag = if (msg.contains("HermesWS") || msg.contains("[submit-drift-abort]") || msg.contains("[session.create")) "HermesWS" else "HermesWebUI"
                    val source = if (!it.sourceId().isNullOrBlank()) "(${it.sourceId()}:${it.lineNumber()})" else ""
                    appendDebugLog(level, tag, msg, source)
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun showDebugLogDialog() {
        runOnUiThread {
            val logs = logRingBuffer.toList().joinToString("\n")
            val scrollView = ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#141414"))
                setPadding(24, 24, 24, 24)
            }
            val textView = TextView(this).apply {
                setTextColor(Color.parseColor("#E0E0E0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                text = if (logs.isNotBlank()) logs else "No logs recorded yet."
            }
            scrollView.addView(textView)

            android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Hermes Debug Logs (${logRingBuffer.size}/$maxLogEntries)")
                .setView(scrollView)
                .setPositiveButton("Copy All") { _, _ ->
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Hermes Debug Logs", logs)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(this, "Copied ${logRingBuffer.size} log lines to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Clear") { _, _ ->
                    logRingBuffer.clear()
                    backgroundExecutor.execute {
                        try { logFile?.writeText("") } catch (_: Throwable) {}
                    }
                    android.widget.Toast.makeText(this, "Logs cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun setupOverlayActions() {
        gatewayUrlInput.setText(nativeBridge.getGatewayUrl())

        btnRetry.setOnClickListener {
            val enteredUrl = gatewayUrlInput.text.toString().trim()
            if (enteredUrl.isNotBlank()) {
                nativeBridge.setGatewayUrl(enteredUrl)
            }
            gatewayStatusText.text = "Checking connection to ${nativeBridge.getGatewayUrl()}..."
            checkGatewayAndLoad()
        }

        btnContinue.setOnClickListener {
            showGatewayOverlay(false)
            loadAppWebUi()
        }
    }

    fun showGatewayOverlay(show: Boolean) {
        runOnUiThread {
            if (::gatewayOverlay.isInitialized) {
                gatewayOverlay.visibility = if (show) View.VISIBLE else View.GONE
                if (show && ::gatewayUrlInput.isInitialized) {
                    gatewayUrlInput.setText(nativeBridge.getGatewayUrl())
                }
            }
        }
    }

    fun launchFilePickerForBridge() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        try {
            filePickerLauncher.launch(intent)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun checkGatewayAndLoad() {
        val currentUrl = nativeBridge.getGatewayUrl()
        gatewayStatusText.text = "Checking Hermes daemon at $currentUrl..."

        backgroundExecutor.execute {
            val isHealthy = try {
                nativeBridge.isGatewayHealthy()
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (isHealthy) {
                    showGatewayOverlay(false)
                    loadAppWebUi()
                } else {
                    gatewayStatusText.text = "Hermes daemon unreachable at $currentUrl.\nEnsure Termux 'hermes' daemon is active, or adjust the gateway host."
                    showGatewayOverlay(true)
                }
            }
        }
    }

    companion object {
        const val APP_WEB_UI_URL = "http://127.0.0.1/assets/hermes/index.html"
    }

    private fun loadAppWebUi() {
        runOnUiThread {
            webView.setBackgroundColor(Color.parseColor("#0A0A0A"))
            webView.loadUrl(APP_WEB_UI_URL)
        }
    }
}
