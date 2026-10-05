package com.coucou.android

/**
 * The island bridge: the whole of upstream's Tauri command surface, turned into data.
 *
 * `coucou/windows` calls exactly one thing to reach the host — `invoke(cmd, args)` —
 * wrapped in `core/bridge.ts` as `Bridge.*`. This object maps that `(cmd, args)` pair to
 * a [BridgeAction] and nothing else: no Android types, no window, no router. The
 * platform side lives in [IslandBridgeHost], which executes an action and produces the
 * JSON envelope the page's Promise settles with.
 *
 * Keeping the mapping pure is what makes the bridge testable on the JVM. It also keeps
 * the contract in one readable place: the frontend is *not* modified, so every command
 * it can send has to be answered here or it silently resolves to `null`.
 */
internal object IslandBridgeCommands {

    /**
     * Panel margin on each side, in dp. Matches `layout.ts` `getPanelWidth`, which
     * measures its panel as `screenWidth - 32`: the window has to be exactly this wide
     * for the page's own centring to land on the screen centre.
     *
     * There is no wake-strip geometry here any more. The collapsed shape is the native
     * rectangle, which the window wraps rather than measures, so nothing has to be sized
     * for a hidden page state.
     */
    private const val SCREEN_MARGIN_DP = 16

    /** Largest island height, from `layout.ts` `PANEL_H`; used as a sanity bound. */
    private const val MAX_HEIGHT_CSS = 320

    /** What a command asks the host to do. */
    sealed class BridgeAction {
        /** Resolve the Promise with [json] verbatim (already-encoded JSON). */
        data class Reply(val json: String) : BridgeAction()

        /** Reject the Promise with [message] — upstream renders it in its note card. */
        data class Fail(val message: String) : BridgeAction()

        /** Resolve the Promise with `null`, which is how every non-throwing command answers. */
        object ReplyNull : BridgeAction()

        /**
         * Hand over the boot payload. Split out because it is the one command whose
         * answer depends on host state: the persisted settings and the real screen.
         */
        object Boot : BridgeAction()

        /** The page pushed its island rect; the window has to follow. */
        data class SetIslandRect(val widthCss: Double, val heightCss: Double) : BridgeAction()

        /** Page asked for the window to shrink to the wake strip, or come back. */
        data class SetCollapsed(val collapsed: Boolean) : BridgeAction()

        /**
         * The page took hold of the drag handle on its top bar.
         *
         * Parameterless on purpose: the host already owns the window position, so a base
         * it has to be told about is a second source of truth.
         */
        object DragStart : BridgeAction()

        /**
         * The page moved the drag handle by ([dx], [dy]) CSS px.
         *
         * A delta, not an absolute position, because the page only ever knows how far its
         * own pointer has travelled; it has no idea where the window sits on screen.
         */
        data class DragBy(val dx: Double, val dy: Double) : BridgeAction()

        /** The page let go of the drag handle; the host clamps and settles. */
        object DragEnd : BridgeAction()

        /** Page asked for (or gave up) keyboard focus on the chat field. */
        data class SetFocused(val focused: Boolean) : BridgeAction()

        /** One chat turn, routed through [CommandRouter]. */
        data class ChatSend(val query: String) : BridgeAction()

        /** Open a URL in the system browser. */
        data class OpenUrl(val url: String) : BridgeAction()

        /** Persist the settings the user just changed in the settings card. */
        data class SaveSettings(val settings: IslandSettings) : BridgeAction()

        /** Open this app's own screen; the desktop build opens a second window. */
        object OpenSettingsWindow : BridgeAction()

        /** Stop the service. */
        object Quit : BridgeAction()

        /** Recompute the window position after a screen change. */
        object Reposition : BridgeAction()

        /** Mirror a line into logcat. */
        data class Log(val message: String) : BridgeAction()

        /** A secret was probed; only its existence ever leaves the host. */
        data class SecretPresent(val key: String) : BridgeAction()
    }

    /** The settings the island card exposes, mirroring upstream's `Settings`. */
    data class IslandSettings(
        val soundEnabled: Boolean = true,
        val soundVolume: Double = 0.12,
        val autoCloseSeconds: Int = 15,
        val activeIntegrations: List<String> = DEFAULT_INTEGRATIONS,
        val hooksInstalled: Boolean = false
    ) {
        companion object {
            /** Upstream `DEFAULT_SETTINGS.activeIntegrations`, minus the ones we cannot poll. */
            val DEFAULT_INTEGRATIONS = listOf("integration_resend", "integration_vercel", "integration_github")
        }
    }

    /**
     * Screen metrics the host reports back in `boot`.
     *
     * @param widthPx physical pixels, what `WindowManager` wants.
     * @param density px per dp, the scale between what the window wants and what the
     *   page lays out in.
     */
    data class Screen(val widthPx: Int, val heightPx: Int, val density: Float) {
        /** px per dp, with a usable value even if the host reported a broken density. */
        val scale: Double get() = if (density > 0f) density.toDouble() else 1.0

        /**
         * Width in dp — the number the page actually wants.
         *
         * `boot` reports dp because that is the unit the island lays out in: on Android
         * a CSS px *is* a dp (`CoucouIslandWebView` leaves the viewport at
         * `device-width`), so `screen.width` is directly comparable with the px the page
         * draws and measures in. Reporting physical px here is what made `boot.screen`
         * come out ~2.75× too wide on a 1080×420dpi device.
         */
        val widthDp: Double get() = round2(widthPx.toDouble() / scale)

        /** Height in dp, for the same reason as [widthDp]. */
        val heightDp: Double get() = round2(heightPx.toDouble() / scale)
    }

    /**
     * Reads one command off the wire.
     *
     * Unknown commands resolve to `null` rather than failing: upstream wraps
     * `call()` in a try/catch and treats a rejection as `null`, so a command we do not
     * model degrades to "nothing happened" instead of breaking a view.
     */
    fun plan(command: String, argsJson: String): BridgeAction {
        val args = Json.parseObject(argsJson)
        return when (command) {
            "boot" -> BridgeAction.Boot
            "chat_send" -> chatSend(args)
            "set_island_rect" -> islandRect(args)
            "set_collapsed" -> BridgeAction.SetCollapsed(args.boolOrNull("collapsed") ?: false)
            "drag_start" -> BridgeAction.DragStart
            "drag_by" -> dragBy(args)
            "drag_end" -> BridgeAction.DragEnd
            "focus_window" -> BridgeAction.SetFocused(args.boolOrNull("focused") ?: false)
            "open_url" -> openUrl(args)
            "save_settings" -> saveSettings(args)
            "open_settings_window" -> BridgeAction.OpenSettingsWindow
            "quit_app" -> BridgeAction.Quit
            "reposition" -> BridgeAction.Reposition
            "log_line" -> BridgeAction.Log(args.stringOrNull("message").orEmpty())
            "secret_present" -> BridgeAction.SecretPresent(args.stringOrNull("key").orEmpty())
            else -> BridgeAction.ReplyNull
        }
    }

    private fun chatSend(args: Map<String, Any?>): BridgeAction {
        val query = args.stringOrNull("query")?.trim().orEmpty()
        // Upstream surfaces a rejection in its note card, which is the honest outcome
        // for input we cannot act on — better than a reply the user never asked for.
        return if (query.isEmpty()) {
            BridgeAction.Fail("Nothing to send.")
        } else {
            BridgeAction.ChatSend(query)
        }
    }

    private fun dragBy(args: Map<String, Any?>): BridgeAction {
        val dx = args.doubleOrNull("dx")
        val dy = args.doubleOrNull("dy")
        // A non-finite delta would put the window somewhere unrecoverable, and the page
        // recovers from a dropped move better than from a NaN position.
        if (dx == null || dy == null || !dx.isFinite() || !dy.isFinite()) {
            return BridgeAction.ReplyNull
        }
        return BridgeAction.DragBy(dx, dy)
    }

    private fun islandRect(args: Map<String, Any?>): BridgeAction {
        val width = args.doubleOrNull("width")
        val height = args.doubleOrNull("height")
        if (width == null || height == null || width < 0 || height < 0 || height > MAX_HEIGHT_CSS * 4) {
            return BridgeAction.ReplyNull
        }
        return BridgeAction.SetIslandRect(width, height)
    }

    private fun openUrl(args: Map<String, Any?>): BridgeAction {        val url = args.stringOrNull("url")?.trim().orEmpty()
        // Only http(s): the island can be driven by page content, and a `javascript:`
        // or `intent:` URL from an overlay would be a real hole.
        val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
        return if (url.isEmpty() || (scheme != "http" && scheme != "https")) {
            BridgeAction.ReplyNull
        } else {
            BridgeAction.OpenUrl(url)
        }
    }

    private fun saveSettings(args: Map<String, Any?>): BridgeAction =
        BridgeAction.SaveSettings(settingsFrom(args.objectOrEmpty("settings")) ?: ISLAND_SETTINGS)

    /** Reads upstream's `Settings`; null when the payload is not shaped like one. */
    fun settingsFrom(raw: Map<String, Any?>): IslandSettings? {
        if (raw.isEmpty()) return null
        val soundEnabled = raw.boolOrNull("soundEnabled") ?: return null
        val volume = raw.doubleOrNull("soundVolume") ?: return null
        val autoClose = raw.doubleOrNull("autoCloseInterval") ?: return null
        val integrations = (raw["activeIntegrations"] as? List<*>)
            ?.mapNotNull { it as? String }
            ?: return null
        return IslandSettings(
            soundEnabled = soundEnabled,
            // Upstream clamps to 0..0.2; keep that so a hostile payload cannot deafen.
            soundVolume = volume.coerceIn(0.0, MAX_VOLUME),
            autoCloseSeconds = autoClose.toInt().coerceIn(1, 3600),
            activeIntegrations = integrations,
            hooksInstalled = raw.boolOrNull("hooksInstalled") ?: false
        )
    }

    /** `boot` payload: exactly the fields the island reads unconditionally. */
    fun bootJson(settings: String, screen: Screen?): String {
        val screenJson = if (screen == null) {
            "null"
        } else {
            "{" +
                "\"x\":0," +
                "\"y\":0," +
                // dp, not px: the page lays out in CSS px, which is dp on Android.
                "\"width\":${screen.widthDp}," +
                "\"height\":${screen.heightDp}," +
                "\"scale\":${screen.scale}" +
                "}"
        }
        return "{" +
            "\"settings\":$settings," +
            "\"screen\":$screenJson," +
            "\"version\":${Json.quote(VERSION)}," +
            "\"hookPath\":\"\"," +
            // Android has no global cursor, and the island's own fallback (poll the page
            // for it) is a desktop-only path, so it is off here.
            "\"cursorPoll\":false" +
            "}"
    }

    /** `boot` payload for a host that has no screen metrics to report. */
    fun bootJson(settings: IslandSettings, screen: Screen? = null): String =
        bootJson(settingsJson(settings), screen)

    /** Upstream's `Settings` in the camelCase it spreads over `State.settings`. */
    fun settingsJson(settings: IslandSettings): String = "{" +
        "\"soundEnabled\":${settings.soundEnabled}," +
        "\"soundVolume\":${settings.soundVolume}," +
        "\"autoCloseInterval\":${settings.autoCloseSeconds}," +
        "\"absenceInterval\":$ABSENCE_INTERVAL," +
        "\"activeIntegrations\":[${settings.activeIntegrations.joinToString(",") { Json.quote(it) }}]," +
        "\"screen\":\"primary\"," +
        "\"autostart\":false," +
        "\"hooksInstalled\":${settings.hooksInstalled}," +
        "\"model\":\"$MODEL\"" +
        "}"

    /**
     * The reply the chat view dereferences without a guard (`reply.text`), so it must
     * always be an object with a non-empty `text`.
     */
    fun chatReply(text: String): String = "{\"text\":${Json.quote(text)}}"

    /**
     * Window bounds, in physical pixels, for a panel [islandHeightCss] tall on a
     * [screenWidthPx]-wide screen at [density].
     *
     * The window *is* the page's panel: the screen less [SCREEN_MARGIN_DP] on each side.
     * That width is not a guess — `layout.ts` `getPanelWidth` measures the panel as
     * `screenWidth - 32` and centres the island in it, so the window has to be exactly
     * that or the prompt box is drawn off-centre, and anything wider spills past the
     * screen edge. The REDO brief asks for the same: screen width minus margins.
     *
     * Height follows the island, scaled by [density]: the island's CSS px are dp on
     * Android, so dp × density is the physical px the window has to be tall. (Scaling the
     * fixed 720px desktop stage to the screen instead — what this used to do — put the
     * island's own 12.5px type at ~6dp on a phone.)
     */
    fun windowBounds(islandHeightCss: Double, density: Float, screenWidthPx: Int): IntArray {
        val margin = marginPx(density)
        val width = (screenWidthPx - 2 * margin).coerceAtLeast(1)
        val height = ((islandHeightCss * density).toInt() + 2 * margin).coerceAtLeast(1)
        return intArrayOf(width, height, margin)
    }

    /** The panel margin in physical pixels; the density guard keeps it non-zero. */
    private fun marginPx(density: Float): Int {
        val safe = if (density > 0f) density else 1f
        return (SCREEN_MARGIN_DP * safe).toInt()
    }

    /**
     * Keeps a [widthPx] x [heightPx] window wholly on a [screenWidthPx] x [screenHeightPx]
     * screen, and answers `x, y`.
     *
     * The collapsed rectangle is positioned by the user, and the expanded panel inherits
     * wherever that left it — but a 90%-wide panel parked where a 56dp rectangle was is
     * mostly off screen, so the position is re-clamped on every shape change rather than
     * only while dragging.
     *
     * A non-positive size means "not measured yet" (`WRAP_CONTENT`, or measured on a later
     * layout pass); the window then cannot be wider than the screen by definition, so only
     * the origin is bounded.
     */
    fun clampToScreen(
        x: Int,
        y: Int,
        widthPx: Int,
        heightPx: Int,
        screenWidthPx: Int,
        screenHeightPx: Int
    ): IntArray {
        val maxX = if (widthPx in 1..screenWidthPx) screenWidthPx - widthPx else 0
        val maxY = if (heightPx in 1..screenHeightPx) screenHeightPx - heightPx else 0
        return intArrayOf(
            x.coerceIn(0, maxOf(0, maxX)),
            y.coerceIn(0, maxOf(0, maxY))
        )
    }

    /** Two decimals is well under a pixel on any real screen and keeps `boot` readable. */
    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    /**
     * Maps one intercepted request path to the asset it should open, relative to the
     * bundle root [prefix].
     *
     * The bundle lives in `assets/<prefix>/` but the page is loaded *from* that prefix
     * and requests its bundle at the origin root (`/assets/x.js`, `/tauri-shim.js`), so
     * the leading `prefix/` has to come off the document request without touching the
     * absolute ones. Getting this wrong 404s the main document, which means no page at
     * all — hence a test rather than a comment.
     */
    fun assetPathFor(path: String, prefix: String): String =
        path.trimStart('/').removePrefix("$prefix/").ifEmpty { "index.html" }

    /** Success envelope understood by `tauri-shim.js`. */
    fun envelope(valueJson: String): String = "{\"ok\":true,\"value\":$valueJson}"

    /** Failure envelope understood by `tauri-shim.js`. */
    fun errorEnvelope(message: String): String =
        "{\"ok\":false,\"error\":${Json.quote(message)}}"

    /** Envelope for a command whose return value upstream ignores. */
    fun nullEnvelope(): String = envelope("null")

    /** Default settings: upstream's, minus the integrations Android cannot poll. */
    val ISLAND_SETTINGS = IslandSettings()

    private const val VERSION = "1.0.0-android"
    private const val MODEL = "claude-opus-5"
    private const val ABSENCE_INTERVAL = 180
    private const val MAX_VOLUME = 0.2
}