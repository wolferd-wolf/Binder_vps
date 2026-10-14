package com.nousresearch.hermes.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

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

        // Initial ping check in background
        checkGatewayAndLoad()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (gatewayOverlay.visibility == View.VISIBLE) {
                    gatewayOverlay.visibility = View.GONE
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun configureEdgeToEdgeDark() {
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

                try {
                    filePickerLauncher.launch(intent)
                    return true
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
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
            gatewayOverlay.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                gatewayUrlInput.setText(nativeBridge.getGatewayUrl())
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
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun checkGatewayAndLoad() {
        val currentUrl = nativeBridge.getGatewayUrl()
        gatewayStatusText.text = "Checking Hermes daemon at $currentUrl..."

        backgroundExecutor.execute {
            val isHealthy = nativeBridge.isGatewayHealthy()
            runOnUiThread {
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
        val assetPath = "hermes/index.html"
        val hasAsset = try {
            val files = assets.list("hermes")
            files != null && files.contains("index.html")
        } catch (e: IOException) {
            false
        }

        if (hasAsset) {
            webView.loadUrl("file:///android_asset/$assetPath")
        } else {
            // Development fallback placeholder
            val fallbackHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <style>
                        body {
                            background-color: #0A0A0A;
                            color: #FFFFFF;
                            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            justify-content: center;
                            height: 100vh;
                            margin: 0;
                            padding: 24px;
                            box-sizing: border-box;
                            text-align: center;
                        }
                        h1 { font-size: 24px; margin-bottom: 8px; color: #FF7043; }
                        p { font-size: 14px; color: #8A8F98; line-height: 1.5; max-width: 400px; }
                        .badge {
                            background-color: #1A1A1A;
                            border: 1px solid #333;
                            padding: 6px 12px;
                            border-radius: 16px;
                            font-size: 12px;
                            margin-top: 16px;
                            color: #A0A0A0;
                        }
                        button {
                            margin-top: 24px;
                            padding: 10px 20px;
                            background: #FF7043;
                            color: #FFF;
                            border: none;
                            border-radius: 8px;
                            font-size: 14px;
                            cursor: pointer;
                        }
                    </style>
                </head>
                <body>
                    <h1>Hermes Desktop</h1>
                    <p>WebUI bundle is staging or waiting for local gateway.</p>
                    <div class="badge">Gateway: ${nativeBridge.getGatewayUrl()}</div>
                    <button onclick="if(window.HermesAndroid) window.HermesAndroid.showGatewayOverlay()">Gateway Settings</button>
                </body>
                </html>
            """.trimIndent()
            webView.loadDataWithBaseURL("http://127.0.0.1:9119", fallbackHtml, "text/html", "UTF-8", null)
        }
    }
}
