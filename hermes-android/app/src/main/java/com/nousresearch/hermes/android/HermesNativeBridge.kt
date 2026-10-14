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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

        private val TOKEN_REGEX = Regex("""window\.__HERMES_SESSION_TOKEN__\s*=\s*["']([^"']+)["']""")

        fun parseSessionToken(html: String): String? {
            val match = TOKEN_REGEX.find(html)
            return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        }
    }

    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return DEFAULT_GATEWAY_URL
        return if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else {
            trimmed
        }
    }

    @JavascriptInterface
    fun getGatewayUrl(): String {
        val stored = prefs.getString(PREF_GATEWAY_URL, DEFAULT_GATEWAY_URL) ?: DEFAULT_GATEWAY_URL
        return normalizeUrl(stored)
    }

    @JavascriptInterface
    fun setGatewayUrl(url: String) {
        val normalized = normalizeUrl(url)
        prefs.edit().putString(PREF_GATEWAY_URL, normalized).apply()
    }

    @JavascriptInterface
    fun isGatewayHealthy(): Boolean {
        // Guard against NetworkOnMainThreadException
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false
        }
        return try {
            val gateway = getGatewayUrl()
            val conn = (URL(gateway).openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            val responseCode = conn.responseCode
            conn.disconnect()
            responseCode in 200..399
        } catch (_: Throwable) {
            false
        }
    }

    @JavascriptInterface
    fun checkGatewayHealthAsync(callbackFunctionName: String) {
        executor.execute {
            val healthy = isGatewayHealthy()
            webView.post {
                val js = "if (typeof window['$callbackFunctionName'] === 'function') { window['$callbackFunctionName']($healthy); }"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    /**
     * Synchronously fetches the session token from http://127.0.0.1:9119/ (or configured gateway)
     * by executing an HTTP GET on a background executor thread, and parses
     * `window.__HERMES_SESSION_TOKEN__="<token>"` from the HTML response body.
     *
     * No caching: queries the server fresh on every invocation.
     * Returns the token string on success, or a clear error string prefixed with "ERROR:"
     * if the server is unreachable or the token is missing.
     */
    @JavascriptInterface
    fun fetchSessionToken(): String {
        return try {
            val future = executor.submit(Callable { doFetchSessionToken() })
            try {
                future.get(2800, TimeUnit.MILLISECONDS)
            } catch (te: TimeoutException) {
                future.cancel(true)
                "ERROR: Timed out fetching session token from ${getGatewayUrl()}"
            }
        } catch (e: ExecutionException) {
            "ERROR: ${e.cause?.message ?: e.message}"
        } catch (e: Throwable) {
            "ERROR: ${e.message}"
        }
    }

    @JavascriptInterface
    fun getSessionToken(): String = fetchSessionToken()

    @JavascriptInterface
    fun fetchSessionTokenAsync(callbackFunctionName: String) {
        executor.execute {
            val result = doFetchSessionToken()
            webView.post {
                val escaped = JSONObject.quote(result)
                val js = "if (typeof window['$callbackFunctionName'] === 'function') { window['$callbackFunctionName']($escaped); }"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    fun doFetchSessionToken(): String {
        val base = getGatewayUrl().trim().removeSuffix("/")
        val targetUrl = "$base/"
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(targetUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
                instanceFollowRedirects = true
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
                setRequestProperty("User-Agent", "HermesAndroid/1.0")
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return "ERROR: Server returned HTTP $responseCode at $targetUrl"
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val token = parseSessionToken(body)
            if (token == null) {
                "ERROR: Session token not found in response from $targetUrl"
            } else {
                token
            }
        } catch (e: IOException) {
            "ERROR: Server unreachable at $targetUrl (${e.message ?: e.javaClass.simpleName})"
        } catch (e: Throwable) {
            "ERROR: Failed to fetch session token (${e.message ?: e.javaClass.simpleName})"
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            try {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        activity.runOnUiThread {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (_: Throwable) {}
        }
    }

    @JavascriptInterface
    fun triggerFilePicker() {
        activity.runOnUiThread {
            activity.launchFilePickerForBridge()
        }
    }

    @JavascriptInterface
    fun showGatewayOverlay() {
        activity.runOnUiThread {
            activity.showGatewayOverlay(true)
        }
    }

    @JavascriptInterface
    fun hideGatewayOverlay() {
        activity.runOnUiThread {
            activity.showGatewayOverlay(false)
        }
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        return try {
            val json = JSONObject()
            json.put("appName", "Hermes Desktop Android")
            json.put("versionName", BuildConfig.VERSION_NAME)
            json.put("versionCode", BuildConfig.VERSION_CODE)
            json.put("buildTime", BuildConfig.BUILD_TIME)
            json.put("gitCommit", BuildConfig.GIT_COMMIT)
            json.put("gatewayUrl", getGatewayUrl())
            json.toString()
        } catch (_: Throwable) {
            "{}"
        }
    }
}
