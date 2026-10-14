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

        fun isAllowedProxyTarget(url: URL): Boolean {
            val host = url.host ?: ""
            val port = url.port
            val protocol = url.protocol ?: ""
            val path = url.path ?: ""
            return protocol.equals("http", ignoreCase = true) &&
                    (host == "127.0.0.1" || host == "localhost") &&
                    (port == 9119) &&
                    (path.startsWith("/api/") || path == "/api")
        }
    }

    @Volatile
    private var cachedSessionToken: String? = null

    fun getOrFetchSessionToken(forceRefresh: Boolean = false): String {
        val cached = cachedSessionToken
        if (!forceRefresh && cached != null && !cached.startsWith("ERROR:")) {
            return cached
        }
        val fetched = doFetchSessionToken()
        if (!fetched.startsWith("ERROR:")) {
            cachedSessionToken = fetched
        }
        return fetched
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
                cachedSessionToken = token
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

    private class ProxyCallResponse(
        val statusCode: Int,
        val statusMessage: String,
        val headers: Map<String, String>,
        val bodyBytes: ByteArray,
        val isBinary: Boolean
    )

    @JavascriptInterface
    fun proxyHttpRequest(requestJson: String): String {
        return try {
            val future = executor.submit(Callable {
                doProxyHttpRequest(requestJson)
            })
            future.get(15, TimeUnit.SECONDS)
        } catch (te: TimeoutException) {
            JSONObject().apply {
                put("status", 0)
                put("statusText", "Timeout")
                put("error", "Proxy request timed out after 15 seconds")
            }.toString()
        } catch (e: Throwable) {
            JSONObject().apply {
                put("status", 0)
                put("statusText", "Error")
                put("error", e.message ?: e.javaClass.simpleName)
            }.toString()
        }
    }

    private fun doProxyHttpRequest(requestJson: String): String {
        val req = try {
            JSONObject(requestJson)
        } catch (e: Exception) {
            return JSONObject().apply {
                put("status", 400)
                put("statusText", "Bad Request")
                put("error", "Invalid JSON request: ${e.message}")
            }.toString()
        }

        val method = req.optString("method", "GET").uppercase()
        val rawUrl = req.optString("url", "")
        val rawPath = req.optString("path", "")
        val targetUrlStr = when {
            rawUrl.isNotBlank() -> rawUrl
            rawPath.startsWith("/") -> "http://127.0.0.1:9119$rawPath"
            else -> "http://127.0.0.1:9119/$rawPath"
        }

        val parsedUrl = try {
            URL(targetUrlStr)
        } catch (e: Exception) {
            return JSONObject().apply {
                put("status", 400)
                put("statusText", "Bad Request")
                put("error", "Malformed URL: $targetUrlStr")
            }.toString()
        }

        if (!isAllowedProxyTarget(parsedUrl)) {
            activity.appendDebugLog("WARN", "HermesWS", "[REST proxy rejected] Target: $targetUrlStr (only http://127.0.0.1:9119/api/* permitted)")
            return JSONObject().apply {
                put("status", 403)
                put("statusText", "Forbidden")
                put("error", "Security violation: Proxy is strictly restricted to http://127.0.0.1:9119/api/*")
            }.toString()
        }

        val headersObj = req.optJSONObject("headers")
        val bodyStr = if (req.has("body") && !req.isNull("body")) req.getString("body") else null
        val isBase64 = req.optBoolean("isBase64", false)
        val requestBytes: ByteArray? = when {
            bodyStr == null -> null
            isBase64 -> java.util.Base64.getDecoder().decode(bodyStr)
            else -> bodyStr.toByteArray(Charsets.UTF_8)
        }

        val startTime = System.currentTimeMillis()
        var token = getOrFetchSessionToken()
        var response = executeHttpCall(parsedUrl, method, headersObj, requestBytes, token)

        // 401 retry: re-mint session token once and retry
        if (response.statusCode == 401) {
            activity.appendDebugLog("WARN", "HermesWS", "[REST proxy] 401 on ${parsedUrl.path}, re-minting session token and retrying once...")
            token = getOrFetchSessionToken(forceRefresh = true)
            if (!token.startsWith("ERROR:")) {
                response = executeHttpCall(parsedUrl, method, headersObj, requestBytes, token)
            }
        }

        val durationMs = System.currentTimeMillis() - startTime
        activity.appendDebugLog("INFO", "HermesWS", "[REST proxy] $method ${parsedUrl.path} -> HTTP ${response.statusCode} (${durationMs}ms)")

        return JSONObject().apply {
            put("status", response.statusCode)
            put("statusText", response.statusMessage)
            val headersJson = JSONObject()
            response.headers.forEach { (k, v) -> headersJson.put(k, v) }
            put("headers", headersJson)
            if (response.isBinary) {
                put("body", java.util.Base64.getEncoder().encodeToString(response.bodyBytes))
                put("isBase64", true)
            } else {
                put("body", String(response.bodyBytes, Charsets.UTF_8))
                put("isBase64", false)
            }
        }.toString()
    }

    private fun executeHttpCall(
        url: URL,
        method: String,
        headers: JSONObject?,
        requestBytes: ByteArray?,
        token: String
    ): ProxyCallResponse {
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 10000
                instanceFollowRedirects = true
                useCaches = false

                if (headers != null) {
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (key.equals("Host", ignoreCase = true) ||
                            key.equals("Content-Length", ignoreCase = true) ||
                            key.equals("Connection", ignoreCase = true) ||
                            key.equals("X-Hermes-Session-Token", ignoreCase = true)
                        ) {
                            continue
                        }
                        setRequestProperty(key, headers.optString(key, ""))
                    }
                }

                if (!token.startsWith("ERROR:")) {
                    setRequestProperty("X-Hermes-Session-Token", token)
                }

                if (requestBytes != null && method != "GET" && method != "HEAD") {
                    doOutput = true
                    setRequestProperty("Content-Length", requestBytes.size.toString())
                    outputStream.use { os ->
                        os.write(requestBytes)
                        os.flush()
                    }
                }
            }

            val statusCode = conn.responseCode
            val statusMessage = conn.responseMessage ?: (if (statusCode in 200..299) "OK" else "Error")

            val respHeaders = mutableMapOf<String, String>()
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && values != null) {
                    respHeaders[key.lowercase(java.util.Locale.US)] = values.joinToString(", ")
                }
            }

            val stream = if (statusCode in 200..399) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }

            val bodyBytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val contentType = respHeaders["content-type"] ?: ""
            val isBinary = contentType.startsWith("image/", ignoreCase = true) ||
                    contentType.startsWith("audio/", ignoreCase = true) ||
                    contentType.startsWith("video/", ignoreCase = true) ||
                    contentType.contains("octet-stream", ignoreCase = true)

            return ProxyCallResponse(statusCode, statusMessage, respHeaders, bodyBytes, isBinary)
        } catch (e: IOException) {
            val errMsg = "I/O error during $method ${url.path}: ${e.message ?: e.javaClass.simpleName}"
            return ProxyCallResponse(
                statusCode = 502,
                statusMessage = "Bad Gateway",
                headers = mapOf("content-type" to "application/json"),
                bodyBytes = JSONObject().put("error", errMsg).toString().toByteArray(Charsets.UTF_8),
                isBinary = false
            )
        } catch (t: Throwable) {
            val errMsg = "Unexpected error during $method ${url.path}: ${t.message ?: t.javaClass.simpleName}"
            return ProxyCallResponse(
                statusCode = 500,
                statusMessage = "Internal Error",
                headers = mapOf("content-type" to "application/json"),
                bodyBytes = JSONObject().put("error", errMsg).toString().toByteArray(Charsets.UTF_8),
                isBinary = false
            )
        } finally {
            try {
                conn?.disconnect()
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
