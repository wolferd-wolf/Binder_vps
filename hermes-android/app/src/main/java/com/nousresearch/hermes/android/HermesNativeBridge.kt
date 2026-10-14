package com.nousresearch.hermes.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class HermesNativeBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {
    private val prefs: SharedPreferences =
        activity.getSharedPreferences("hermes_prefs", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        const val PREF_GATEWAY_URL = "gateway_url"
        const val DEFAULT_GATEWAY_URL = "http://127.0.0.1:9119"
        const val BRIDGE_NAME = "HermesAndroid"
    }

    @JavascriptInterface
    fun getGatewayUrl(): String {
        return prefs.getString(PREF_GATEWAY_URL, DEFAULT_GATEWAY_URL) ?: DEFAULT_GATEWAY_URL
    }

    @JavascriptInterface
    fun setGatewayUrl(url: String) {
        val trimmed = url.trim()
        val normalized = if (trimmed.endsWith("/")) trimmed.dropLast(1) else trimmed
        prefs.edit().putString(PREF_GATEWAY_URL, normalized).apply()
    }

    @JavascriptInterface
    fun isGatewayHealthy(): Boolean {
        return try {
            val gateway = getGatewayUrl()
            val conn = (URL(gateway).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
            }
            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode in 200..399
        } catch (e: Exception) {
            false
        }
    }

    @JavascriptInterface
    fun checkGatewayHealthAsync(callbackFunctionName: String) {
        executor.execute {
            val healthy = isGatewayHealthy()
            mainHandler.post {
                val js = "if (typeof window['$callbackFunctionName'] === 'function') { window['$callbackFunctionName']($healthy); }"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // ignore
        }
    }

    @JavascriptInterface
    fun triggerFilePicker() {
        mainHandler.post {
            activity.launchFilePickerForBridge()
        }
    }

    @JavascriptInterface
    fun showGatewayOverlay() {
        mainHandler.post {
            activity.showGatewayOverlay(true)
        }
    }

    @JavascriptInterface
    fun hideGatewayOverlay() {
        mainHandler.post {
            activity.showGatewayOverlay(false)
        }
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        val json = JSONObject()
        json.put("appName", "Hermes Desktop Android")
        json.put("versionName", BuildConfig.VERSION_NAME)
        json.put("versionCode", BuildConfig.VERSION_CODE)
        json.put("buildTime", BuildConfig.BUILD_TIME)
        json.put("gitCommit", BuildConfig.GIT_COMMIT)
        json.put("gatewayUrl", getGatewayUrl())
        return json.toString()
    }
}
