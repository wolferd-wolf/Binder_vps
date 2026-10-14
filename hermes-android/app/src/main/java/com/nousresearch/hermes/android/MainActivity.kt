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
            configureEdgeToEdgeDark()
            setContentView(R.layout.activity_main)

            webView = findViewById(R.id.webView)
            progressBar = findViewById(R.id.progressBar)
            gatewayOverlay = findViewById(R.id.gatewayOverlay)
            gatewayStatusText = findViewById(R.id.gatewayStatusText)
            gatewayUrlInput = findViewById(R.id.gatewayUrlInput)
            btnRetry = findViewById(R.id.btnRetry)
            btnContinue = findViewById(R.id.btnContinue)

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
                return super.onConsoleMessage(consoleMessage)
            }
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

    private fun loadAppWebUi() {
        runOnUiThread {
            webView.setBackgroundColor(Color.parseColor("#0A0A0A"))
            webView.loadUrl("file:///android_asset/hermes/index.html")
        }
    }
}
