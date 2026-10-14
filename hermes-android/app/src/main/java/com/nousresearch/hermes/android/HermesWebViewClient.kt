package com.nousresearch.hermes.android

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.webkit.WebViewAssetLoader
import java.net.URLConnection

class HermesWebViewClient(
    private val context: Context,
    private val progressBar: ProgressBar?,
    private val onPageFinishedListener: ((String?) -> Unit)? = null
) : WebViewClient() {

    private val assetLoader127 = WebViewAssetLoader.Builder()
        .setDomain("127.0.0.1")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    private val assetLoaderLocalhost = WebViewAssetLoader.Builder()
        .setDomain("localhost")
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        val scheme = url.scheme?.lowercase() ?: ""
        val host = url.host?.lowercase() ?: ""
        val port = url.port

        // Only intercept requests for http://127.0.0.1 and http://localhost (port 80 or default).
        // NEVER intercept port 9119 (the gateway server) or any other non-standard port!
        // These requests must go directly to the live server on the network.
        if (port != -1 && port != 80) {
            return null
        }

        if (scheme != "http" && scheme != "https") {
            return null
        }

        if (host != "127.0.0.1" && host != "localhost") {
            return null
        }

        val path = url.path ?: ""

        // Handle root / index.html mappings to hermes/index.html
        if (path == "/assets/hermes" || path == "/assets/hermes/" || path == "/" || path == "/index.html") {
            return try {
                val stream = context.assets.open("hermes/index.html")
                WebResourceResponse("text/html", "utf-8", stream)
            } catch (_: Exception) {
                null
            }
        }

        // Handle root-level assets that might be requested without the /assets/hermes prefix
        if (path == "/apple-touch-icon.png" || path == "/favicon.ico") {
            return try {
                val stream = context.assets.open("hermes/apple-touch-icon.png")
                WebResourceResponse("image/png", null, stream)
            } catch (_: Exception) {
                null
            }
        }

        if (path.startsWith("/ds-assets/") || path.startsWith("/hermes-frames/")) {
            val assetPath = "hermes" + path
            return try {
                val stream = context.assets.open(assetPath)
                val mime = URLConnection.guessContentTypeFromName(assetPath) ?: "application/octet-stream"
                WebResourceResponse(mime, null, stream)
            } catch (_: Exception) {
                null
            }
        }

        if (path.startsWith("/assets/") && !path.startsWith("/assets/hermes/")) {
            val fallbackPath = "hermes" + path
            try {
                val stream = context.assets.open(fallbackPath)
                val mime = URLConnection.guessContentTypeFromName(fallbackPath) ?: "application/javascript"
                return WebResourceResponse(mime, null, stream)
            } catch (_: Exception) {}
        }

        return assetLoader127.shouldInterceptRequest(url)
            ?: assetLoaderLocalhost.shouldInterceptRequest(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        val host = uri.host?.lowercase() ?: ""
        val scheme = uri.scheme?.lowercase() ?: ""

        // Handle local web assets or local loopback connection
        if (scheme == "file" || host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") {
            return false
        }

        // External URLs open in system browser
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        progressBar?.visibility = View.VISIBLE
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        progressBar?.visibility = View.GONE
        onPageFinishedListener?.invoke(url)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            progressBar?.visibility = View.GONE
        }
    }
}
