package com.coucou.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Hosts the upstream Coucou desktop UI (`coucou/windows`) inside the overlay window.
 *
 * Plan A: the page is served to the WebView from an intercepted origin, so it runs as
 * real same-origin content rather than over `file://` — `fetch("/sounds/…")` and the
 * module graph both need an origin. Nothing reaches the network: every request is
 * answered from `assets/coucou/` or from `res/raw/`.
 *
 * Three things make it behave like the desktop window rather than a browser tab:
 *  - the viewport stays at upstream's `width=device-width`, so one CSS px is one dp and
 *    the page's own measurements are in the same unit as the window's (the REDO brief:
 *    screen width read in dp at runtime, never hardcoded);
 *  - the window is transparent, since `html, body { background: transparent }`;
 *  - sounds are served from `res/raw/`, so the 28 WAVs exist once in the APK.
 */
internal class CoucouIslandWebView private constructor(
    private val context: Context,
    private val bridge: IslandBridgeHost,
    val view: WebView
) {

    /** Told when the page has finished booting, so the host can reveal the island. */
    var onReady: (() -> Unit)? = null

    /** Called with every page navigation the WebView asks us to open externally. */
    var onExternalUrl: ((String) -> Unit)? = null

    /**
     * The document itself failed to load — no `index.html`, a bad bundle URL, no asset.
     *
     * Called instead of leaving an empty window up: a translucent window over a WebView
     * that never painted is indistinguishable from a black screen on the device, which is
     * what @Boss recorded.
     */
    var onFailed: ((String) -> Unit)? = null

    private var pageLoaded = false

    private fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            // Upstream uses no storage at all (no localStorage/IndexedDB/cookies), and
            // refusing it keeps the page from persisting anything about the user.
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            // The 720px stage has to scale to whatever the overlay window is wide.
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            // Upstream's design is in CSS px; a system font scale would desync it from
            // the geometry the page computes in the same units.
            textZoom = 100
            // Sounds are the page's own trigger (Sound.play on a gesture); without this
            // every AudioContext would stay suspended until the first tap.
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            // Nothing here should ever offer a file picker or a print dialog.
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setGeolocationEnabled(false)
            loadsImagesAutomatically = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
        }
        // The island is drawn on transparent, so the page must be too or it would show
        // as an opaque slab over whatever app is behind the overlay — which, on a page that
        // fails to paint, is the black screen @Boss recorded.
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = ViewGroup.OVER_SCROLL_NEVER
        // Long-press would put a text-selection handle on top of the character.
        webView.isLongClickable = false
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        webView.addJavascriptInterface(bridge, NATIVE_INTERFACE)
        webView.addJavascriptInterface(bridge, "CoucouAndroid")
        webView.webViewClient = IslandWebViewClient()
        webView.webChromeClient = IslandChromeClient()
    }

    /** Loads the island. Safe to call again after the process reloads the page. */
    fun load() {
        pageLoaded = false
        view.loadUrl(PAGE_URL)
    }

    /**
     * Reveals the island on its home view once the page is up.
     *
     * With no Tauri events arriving the island stays retracted forever: it plays its
     * greeting, collapses to the compact bar and then hides. The desktop build gets
     * this from the tray (`tray: "open"`), so the same event is emitted here.
     */
    fun reveal(target: String = VIEW_HOME) {
        runCatching {
            view.evaluateJavascript("window.CoucouIsland && window.CoucouIsland.$target()", null)
        }
    }

    /** Pushes a settings payload back to the page, mirroring desktop `save_settings`. */
    fun broadcastSettings(settingsJson: String) {
        runCatching {
            view.evaluateJavascript(
                "window.CoucouIsland && window.CoucouIsland.emit('settings-changed'," +
                    "${Json.quote(settingsJson)})",
                null
            )
        }
    }

    /**
     * Opens the island straight on the prompt view, which is where a tap on the collapsed
     * rectangle is meant to land.
     *
     * Both spellings are tried: the shim's chat entry point, and a prompt-specific one if
     * @Cline's lane adds it. On this page the chat tab *is* the prompt view, so the chat
     * entry point is a correct answer, not a fallback that shows the wrong thing.
     */
    fun showPrompt() {
        runCatching {
            view.evaluateJavascript(
                "window.CoucouIsland && (window.CoucouIsland.showPrompt" +
                    " ? window.CoucouIsland.showPrompt()" +
                    " : window.CoucouIsland.showChat())",
                null
            )
        }
    }

    /**
     * Logs what the page is showing, for @AGY's emulator pass — the island's own state
     * is invisible from outside the WebView, so this is the only readable window into it.
     */
    fun logDescription() {
        runCatching {
            view.evaluateJavascript("window.CoucouIsland ? window.CoucouIsland.describe() : null") { value ->
                Log.i(TAG, "Island state -> ${value ?: "null"}")
            }
        }
    }

    /** Pauses JS timers and animation, e.g. while the screen is off. */
    fun pause() {
        runCatching { view.onPause() }
        runCatching { view.pauseTimers() }
    }

    /** Undoes [pause]. */
    fun resume() {
        runCatching { view.resumeTimers() }
        runCatching { view.onResume() }
    }

    /** Notifies the island that the screen configuration changed (e.g., rotation). */
    fun postScreenChanged() {
        runCatching {
            view.evaluateJavascript(
                "window.dispatchEvent(new Event('resize'))", null
            )
        }
    }

    /** Detaches for good; the WebView cannot be reused afterwards. */
    fun destroy() {
        runCatching { view.stopLoading() }
        runCatching {
            view.webViewClient = WebViewClient()
            view.removeJavascriptInterface(NATIVE_INTERFACE)
        }
        runCatching { view.loadUrl("about:blank") }
        runCatching { view.removeAllViews() }
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        runCatching { view.destroy() }
    }

    private inner class IslandWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url
            // The page never navigates on its own; anything it asks for is a link the
            // user tapped, which belongs in the browser, not in the overlay.
            if (url.host == ASSET_DOMAIN) return false
            onExternalUrl?.invoke(url.toString())
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (pageLoaded) return
            pageLoaded = true
            Log.i(TAG, "Island page ready: $url")
            onReady?.invoke()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            // Sub-resources fail all the time (a sound that is not in res/raw yet) and
            // upstream swallows those itself, so only the document is worth reporting.
            if (!request.isForMainFrame) return
            reportFailure("document ${error.errorCode} (${request.url})")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (!request.isForMainFrame) return
            reportFailure("HTTP ${errorResponse.statusCode} (${request.url})")
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            // Nothing here is ever fetched over the network, so an SSL error means the
            // asset loader handed the WebView something it cannot vouch for. Refuse it and
            // say so, rather than showing an empty window.
            handler.cancel()
            reportFailure("TLS ${error.primaryError} (${error.url})")
        }
    }

    /** Tells the host the page is not coming, and logs the same line for logcat. */
    private fun reportFailure(reason: String) {
        Log.e(TAG, "Island page failed: $reason")
        onFailed?.invoke(reason)
    }

    /**
     * The page's own console, mirrored into logcat under [TAG].
     *
     * Without a `WebChromeClient` the WebView throws `console.error` away. The page is
     * remote content being served out of `assets/`, so a boot that fails does so in the
     * console — and with nothing mirroring it, the only symptom is an empty window and
     * completely silent logcat. This is the difference between "the page is broken" and
     * "the app is broken", which is the whole of @Boss's device report.
     */
    private inner class IslandChromeClient : WebChromeClient() {
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            val text = "page[${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
            when (consoleMessage.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, text)
                ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, text)
                else -> Log.i(TAG, text)
            }
            return true
        }
    }

    /**
     * Answers every request from `assets/coucou/` and the 28 sounds from `res/raw/`.
     *
     * Sounds are deliberately not vendored into the assets bundle: they already live in
     * `res/raw/coucou_*.wav` (@Buffy's lane), and this keeps a single copy in the APK.
     */
    private val assetLoader: WebViewAssetLoader by lazy {
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            // Order matters: the first handler that answers wins, so the sounds must be
            // claimed before the catch-all assets tree is offered.
            .addPathHandler("/sounds/", RawSoundPathHandler(context))
            .addPathHandler("/", PrefixAssetsPathHandler(context, ASSET_ROOT))
            .build()
    }

    companion object {
        private const val TAG = "CoucouIslandWebView"

        /**
         * Builds the island, or returns null when the host cannot provide one — a device
         * without a WebView provider must fall back to the native bubble rather than crash.
         */
        @SuppressLint("SetJavaScriptEnabled")
        fun create(context: Context, bridge: IslandBridgeHost): CoucouIslandWebView? = try {
            // A service context does not carry the application theme, same reason the
            // layouts are inflated through a ContextThemeWrapper.
            val themed = ContextThemeWrapper(context, R.style.Theme_Coucou)
            val webView = WebView(themed)
            val island = CoucouIslandWebView(context, bridge, webView)
            island.configure(webView)
            island
        } catch (e: Exception) {
            Log.e(TAG, "Could not create the island WebView", e)
            null
        }

        /** Intercepted origin. Nothing is ever fetched from it. */
        const val ASSET_DOMAIN = "coucou.android.local"

        /** Must match the `assets/coucou` folder staged by `tools/stage-coucou-web.mjs`. */
        const val ASSET_ROOT = "coucou"
        const val PAGE_URL = "https://$ASSET_DOMAIN/$ASSET_ROOT/index.html"

        /** Name the page's shim calls for the native bridge. */
        const val NATIVE_INTERFACE = "CoucouNative"

        const val VIEW_HOME = "showHome"
        const val VIEW_SETTINGS = "showSettings"
        const val VIEW_CHAT = "showChat"
    }
}

/**
 * Serves `assets/<prefix>/…` for any request path, so a bundle can live in its own
 * folder while still answering the absolute `/assets/…` URLs a Vite build emits.
 *
 * `WebViewAssetLoader.AssetsPathHandler` cannot do this: it maps a path straight onto the
 * assets root, so `https://<domain>/index.html` would look for `assets/index.html`.
 */
private class PrefixAssetsPathHandler(
    private val context: Context,
    private val prefix: String
) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val relative = IslandBridgeCommands.assetPathFor(path, prefix)
        // AssetLoader already normalises, but this is the only thing standing between a
        // request and `assets/`, so the shape is checked rather than trusted.
        if (!relative.matches(SAFE_ASSET_PATH)) return null
        val mime = mimeTypeOf(relative)
        val stream = try {
            context.assets.open("$prefix/$relative")
        } catch (e: IOException) {
            Log.w(TAG, "No island asset '$relative'", e)
            return null
        }
        return WebResourceResponse(mime, CHARSET, stream)
    }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "wav" -> "audio/wav"
        "woff2" -> "font/woff2"
        "map" -> "application/json"
        else -> "application/octet-stream"
    }

    companion object {
        private const val TAG = "CoucouIslandWebView"
        private const val CHARSET = "utf-8"
        private val SAFE_ASSET_PATH = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
    }
}

/**
 * Answers `/sounds/<name>.wav` from `res/raw/coucou_<name>.wav`.
 *
 * The name is validated against the same shape the upstream dev server enforced, so a
 * crafted path cannot walk out of `res/raw`.
 */
private class RawSoundPathHandler(private val context: Context) : WebViewAssetLoader.PathHandler {

    override fun handle(path: String): WebResourceResponse? {
        val name = path.removeSuffix(".wav")
        if (!name.matches(SAFE_NAME) || !path.endsWith(".wav")) return null
        val resourceId = context.resources.getIdentifier(
            RES_PREFIX + name,
            "raw",
            context.packageName
        )
        if (resourceId == 0) return null
        val descriptor = try {
            context.resources.openRawResourceFd(resourceId)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open sound '$name'", e)
            return null
        }
        return WebResourceResponse(MIME_WAV, null, closingStream(descriptor))
    }

    /**
     * Wraps the asset so the file descriptor is released once the decoder has finished
     * with it; a WebView does not close a response stream it did not open itself.
     */
    private fun closingStream(descriptor: AssetFileDescriptor): InputStream =
        object : FilterInputStream(descriptor.createInputStream()) {
            private var closed = false

            override fun close() {
                if (closed) return
                closed = true
                try {
                    super.close()
                } catch (_: IOException) {
                    // Already gone; nothing useful to do.
                } finally {
                    runCatching { descriptor.close() }
                }
            }
        }

    companion object {
        private const val TAG = "CoucouIslandWebView"
        private const val RES_PREFIX = "coucou_"
        private const val MIME_WAV = "audio/wav"
        private val SAFE_NAME = Regex("^[a-z][a-z0-9_]*$")
    }
}