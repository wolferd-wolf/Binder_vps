package com.coucou.android

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.webkit.JavascriptInterface
import com.coucou.android.IslandBridgeCommands.BridgeAction

/**
 * The Android end of the island bridge: the `window.CoucouNative` object the page's
 * shim calls, plus the settings it persists on the way through.
 *
 * Every call arrives on the WebView's private JavaScript thread, not the main thread,
 * so window and IME work is posted by the [Listener] implementations. Returns are
 * synchronous because that is all `addJavascriptInterface` allows — which is also why
 * [Listener.onChatQuery] is a blocking call: the page awaits `reply.text` to render the
 * message, and the route it needs ([CommandRouter]) is blocking by design.
 */
internal class IslandBridgeHost(
    private val context: Context,
    private val listener: Listener
) {

    /** Host-side reactions to a page command. Implementations must be thread-safe. */
    interface Listener {
        /** The page pushed its island rect (CSS px); size the window to match. */
        fun onIslandRect(widthCss: Double, heightCss: Double)

        /** Shrink to the wake strip, or come back to full size. */
        fun onIslandCollapsed(collapsed: Boolean)

        /** The page took hold of its top-bar drag handle. */
        fun onDragStart()

        /** The page moved the drag handle by (dx, dy) CSS px from wherever it started. */
        fun onDragBy(dx: Double, dy: Double)

        /** The page let go; the host clamps the window back onto the screen. */
        fun onDragEnd()

        /** The chat field asked for keyboard focus, or gave it up. */
        fun onIslandFocus(focused: Boolean)

        /** Routes one chat turn and returns the text the page should display. */
        fun onChatQuery(query: String): String

        /** Open [url] outside the overlay. */
        fun onOpenUrl(url: String)

        /** Persist and re-broadcast settings the user changed in the settings card. */
        fun onSettingsChanged(settings: IslandBridgeCommands.IslandSettings)

        /** Screen metrics for the boot payload. */
        fun screen(): IslandBridgeCommands.Screen

        /** Mirror a page log line into logcat. */
        fun onPageLog(message: String)

        /**
         * The page itself failed to load, so there is no island to talk to.
         *
         * Distinct from [onPageLog]: this one has to reach the screen, because the symptom
         * otherwise is an empty window and nothing to read anywhere.
         */
        fun onPageFailed(message: String)

        /** Stop the overlay service. */
        fun onQuitRequested()
    }

    /**
     * Called from JavaScript with the Tauri command and its JSON arguments; answers with
     * the envelope `tauri-shim.js` turns into a resolved or rejected Promise.
     */
    @JavascriptInterface
    fun invoke(command: String?, argsJson: String?): String {
        val cmd = command.orEmpty()
        val action = try {
            IslandBridgeCommands.plan(cmd, argsJson.orEmpty())
        } catch (e: Exception) {
            Log.w(TAG, "Bridge command '$cmd' failed to plan", e)
            BridgeAction.ReplyNull
        }
        return execute(action)
    }

    @JavascriptInterface
    fun collapse(): String {
        listener.onIslandCollapsed(true)
        return IslandBridgeCommands.nullEnvelope()
    }

    @JavascriptInterface
    fun setCollapsed(collapsed: Boolean): String {
        listener.onIslandCollapsed(collapsed)
        return IslandBridgeCommands.nullEnvelope()
    }

    @JavascriptInterface
    fun chatSend(query: String?, contextJson: String? = null): String {
        val q = query.orEmpty().trim()
        if (q.isEmpty()) {
            return IslandBridgeCommands.errorEnvelope("Nothing to send.")
        }
        val replyText = listener.onChatQuery(q)
        return IslandBridgeCommands.envelope(IslandBridgeCommands.chatReply(replyText))
    }

    /** Runs [action] and returns the envelope to hand back to the page. */
    fun execute(action: BridgeAction): String = try {
        when (action) {
            is BridgeAction.Reply -> IslandBridgeCommands.envelope(action.json)
            is BridgeAction.Fail -> IslandBridgeCommands.errorEnvelope(action.message)
            BridgeAction.ReplyNull -> IslandBridgeCommands.nullEnvelope()
            BridgeAction.Boot -> IslandBridgeCommands.envelope(
                IslandBridgeCommands.bootJson(
                    IslandBridgeCommands.settingsJson(restoredSettings()),
                    listener.screen()
                )
            )
            is BridgeAction.ChatSend -> IslandBridgeCommands.envelope(
                IslandBridgeCommands.chatReply(listener.onChatQuery(action.query))
            )
            is BridgeAction.SetIslandRect -> {
                listener.onIslandRect(action.widthCss, action.heightCss)
                IslandBridgeCommands.nullEnvelope()
            }
            is BridgeAction.SetCollapsed -> {
                listener.onIslandCollapsed(action.collapsed)
                IslandBridgeCommands.nullEnvelope()
            }
            BridgeAction.DragStart -> {
                listener.onDragStart()
                IslandBridgeCommands.nullEnvelope()
            }
            is BridgeAction.DragBy -> {
                listener.onDragBy(action.dx, action.dy)
                IslandBridgeCommands.nullEnvelope()
            }
            BridgeAction.DragEnd -> {
                listener.onDragEnd()
                IslandBridgeCommands.nullEnvelope()
            }
            is BridgeAction.SetFocused -> {
                listener.onIslandFocus(action.focused)
                IslandBridgeCommands.nullEnvelope()
            }
            is BridgeAction.OpenUrl -> {
                listener.onOpenUrl(action.url)
                IslandBridgeCommands.nullEnvelope()
            }
            is BridgeAction.SaveSettings -> {
                persist(action.settings)
                listener.onSettingsChanged(action.settings)
                IslandBridgeCommands.nullEnvelope()
            }
            BridgeAction.OpenSettingsWindow -> {
                listener.onOpenUrl(SETTINGS_DEEP_LINK)
                IslandBridgeCommands.nullEnvelope()
            }
            BridgeAction.Quit -> {
                listener.onQuitRequested()
                IslandBridgeCommands.nullEnvelope()
            }
            BridgeAction.Reposition,
            is BridgeAction.Log,
            is BridgeAction.SecretPresent -> IslandBridgeCommands.nullEnvelope()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Bridge action $action failed", e)
        IslandBridgeCommands.nullEnvelope()
    }

    /** The settings the island starts with, restored from [preferences]. */
    fun restoredSettings(): IslandBridgeCommands.IslandSettings {
        val prefs = preferences()
        return IslandBridgeCommands.IslandSettings(
            soundEnabled = prefs.getBoolean(KEY_SOUND_ENABLED, true),
            soundVolume = Double.fromBits(prefs.getLong(KEY_SOUND_VOLUME, DEFAULT_VOLUME_BITS)),
            autoCloseSeconds = prefs.getInt(KEY_AUTO_CLOSE, DEFAULT_AUTO_CLOSE),
            activeIntegrations = IslandBridgeCommands.IslandSettings.DEFAULT_INTEGRATIONS,
            hooksInstalled = false
        )
    }

    private fun persist(settings: IslandBridgeCommands.IslandSettings) {
        preferences().edit()
            .putBoolean(KEY_SOUND_ENABLED, settings.soundEnabled)
            .putLong(KEY_SOUND_VOLUME, settings.soundVolume.toBits())
            .putInt(KEY_AUTO_CLOSE, settings.autoCloseSeconds)
            .apply()
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "CoucouIslandBridge"
        private const val PREFS_NAME = "coucou_island_settings"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_AUTO_CLOSE = "auto_close_seconds"

        private const val DEFAULT_AUTO_CLOSE = 15
        private val DEFAULT_VOLUME_BITS = 0.12.toBits()

        /** Opens this app's own screen from the settings card's "Settings…" link. */
        const val SETTINGS_DEEP_LINK = "coucou://settings"
    }
}