package com.coucou.android

import com.coucou.android.IslandBridgeCommands.BridgeAction
import com.coucou.android.IslandBridgeCommands.IslandSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The island bridge, tested as the [BridgeAction] table it is.
 *
 * These assertions are the contract with upstream's `core/bridge.ts`: a command that
 * stops being answered the way the page expects shows up here as a failing test rather
 * than as a silently dead button on the device.
 */
class IslandBridgeCommandsTest {

    // region boot

    @Test
    fun `boot asks the host for its payload`() {
        assertEquals(BridgeAction.Boot, IslandBridgeCommands.plan("boot", "{}"))
    }

    @Test
    fun `boot payload carries the settings the island reads unconditionally`() {
        val payload = IslandBridgeCommands.bootJson(
            IslandBridgeCommands.settingsJson(IslandSettings()),
            null
        )
        val boot = Json.parseObject(payload)
        val settings = boot["settings"] as Map<*, *>

        // main.ts spreads these over State.settings; a missing key silently keeps the
        // upstream default, so the ones the island reads are asserted explicitly.
        assertEquals(true, settings["soundEnabled"])
        assertEquals(0.12, settings["soundVolume"])
        assertEquals(15.0, settings["autoCloseInterval"])
        assertEquals(false, settings["hooksInstalled"])
        assertEquals(false, boot["cursorPoll"])
        assertTrue(boot["version"] is String)
    }

    @Test
    fun `boot payload reports the screen when the host has metrics`() {
        val screen = IslandBridgeCommands.Screen(1080, 2340, 3f)
        val boot = Json.parseObject(IslandBridgeCommands.bootJson(IslandSettings(), screen))
        val reported = boot["screen"] as Map<*, *>
        assertEquals(1080.0, reported["width"])
        assertEquals(2340.0, reported["height"])
        assertEquals(3.0, reported["scale"])
    }

    @Test
    fun `settings omit integrations the android host cannot poll`() {
        val settings = Json.parseObject(IslandBridgeCommands.settingsJson(IslandSettings()))
        val integrations = settings["activeIntegrations"] as List<*>
        // n8n needs a configured instance and a poller; there is nothing to show.
        assertTrue(integrations.contains("integration_resend"))
        assertTrue(!integrations.contains("integration_n8n"))
    }

    // endregion

    // region chat

    @Test
    fun `chat send carries the query exactly as typed`() {
        val args = Json.write(mapOf("query" to "  open chrome ", "context" to null))
        val action = IslandBridgeCommands.plan("chat_send", args)
        assertEquals(BridgeAction.ChatSend("open chrome"), action)
    }

    @Test
    fun `chat send rejects an empty query instead of guessing`() {
        // chat.ts dereferences reply.text unguarded, so a blank message has to fail
        // loudly rather than come back as an empty reply.
        assertTrue(IslandBridgeCommands.plan("chat_send", """{"query":"   "}""") is BridgeAction.Fail)
        assertTrue(IslandBridgeCommands.plan("chat_send", "{}") is BridgeAction.Fail)
    }

    @Test
    fun `chat reply is an object with text, which is what the view dereferences`() {
        val reply = Json.parseObject(IslandBridgeCommands.chatReply("Opened Chrome"))
        assertEquals("Opened Chrome", reply["text"])
    }

    @Test
    fun `chat reply survives quotes and newlines in the answer`() {
        val reply = Json.parseObject(
            IslandBridgeCommands.chatReply("He said \"hi\"\nthen left\\")
        )
        assertEquals("He said \"hi\"\nthen left\\", reply["text"])
    }

    // endregion

    // region geometry

    @Test
    fun `island rect becomes a window sized to the island plus a margin`() {
        val action = IslandBridgeCommands.plan(
            "set_island_rect",
            """{"x":40,"y":0,"width":640,"height":160}"""
        )
        assertEquals(BridgeAction.SetIslandRect(640.0, 160.0), action)
    }

    @Test
    fun `island rect ignores a payload it cannot trust`() {
        assertEquals(BridgeAction.ReplyNull, IslandBridgeCommands.plan("set_island_rect", "{}"))
        assertEquals(
            BridgeAction.ReplyNull,
            IslandBridgeCommands.plan("set_island_rect", """{"width":-5,"height":160}""")
        )
        assertEquals(
            BridgeAction.ReplyNull,
            IslandBridgeCommands.plan("set_island_rect", """{"width":640,"height":99999}""")
        )
    }

    @Test
    fun `window bounds centre the island and leave the screen row free`() {
        // 720 CSS px stage across a 1080 px screen: 1.5x.
        val bounds = IslandBridgeCommands.windowBounds(640.0, 160.0, 1080, 1080)

        // 640 + 2*8 margin, scaled.
        assertEquals(984, bounds[0])
        assertEquals(264, bounds[1])
        // Centred, so the page's own left:50% centring lands on the screen centre.
        assertEquals((1080 - 984) / 2, bounds[2])
        assertTrue("must not span the whole row", bounds[0] < 1080)
    }

    @Test
    fun `window bounds track the compact bar, not just the expanded panel`() {
        val compact = IslandBridgeCommands.windowBounds(288.0, 32.0, 1080, 1080)
        val expanded = IslandBridgeCommands.windowBounds(640.0, 240.0, 1080, 1080)
        assertTrue(compact[1] < expanded[1])
        assertTrue(compact[0] < expanded[0])
    }

    @Test
    fun `collapsed window is the wake strip`() {
        val bounds = IslandBridgeCommands.collapsedWindowBounds(1080, 1080)
        assertEquals((240 + 16) * 1080 / 720, bounds[0])
        assertTrue("a 6px island still needs room to be tapped", bounds[1] >= 1)
    }

    @Test
    fun `window bounds survive a degenerate screen width`() {
        val bounds = IslandBridgeCommands.windowBounds(640.0, 160.0, 0, 0)
        assertTrue(bounds.all { it >= 0 })
        assertTrue(bounds[0] > 0 && bounds[1] > 0)
    }

    // endregion

    // region settings

    @Test
    fun `save settings is forwarded with the three card controls read`() {
        val args = Json.write(mapOf("settings" to mapOf(
            "soundEnabled" to false,
            "soundVolume" to 0.05,
            "autoCloseInterval" to 30,
            "activeIntegrations" to emptyList<String>()
        )))
        val action = IslandBridgeCommands.plan("save_settings", args)
        assertEquals(
            BridgeAction.SaveSettings(
                IslandSettings(
                    soundEnabled = false,
                    soundVolume = 0.05,
                    autoCloseSeconds = 30,
                    activeIntegrations = emptyList()
                )
            ),
            action
        )
    }

    @Test
    fun `volume is clamped to the range the upstream player allows`() {
        val settings = IslandBridgeCommands.settingsFrom(
            mapOf(
                "soundEnabled" to true,
                "soundVolume" to 9.0,
                "autoCloseInterval" to 15.0,
                "activeIntegrations" to emptyList<String>()
            )
        )
        assertEquals(0.2, settings!!.soundVolume, 1e-9)
    }

    @Test
    fun `settings that are not shaped like settings fall back to the defaults`() {
        assertEquals(null, IslandBridgeCommands.settingsFrom(emptyMap()))
        assertEquals(null, IslandBridgeCommands.settingsFrom(mapOf("soundEnabled" to true)))
    }

    // endregion

    // region focus, collapse, urls

    @Test
    fun `focus window carries the flag the desktop app flips`() {
        assertEquals(
            BridgeAction.SetFocused(true),
            IslandBridgeCommands.plan("focus_window", """{"focused":true}""")
        )
        assertEquals(
            BridgeAction.SetFocused(false),
            IslandBridgeCommands.plan("focus_window", "{}")
        )
    }

    @Test
    fun `collapse carries the flag`() {
        assertEquals(
            BridgeAction.SetCollapsed(true),
            IslandBridgeCommands.plan("set_collapsed", """{"collapsed":true}""")
        )
    }

    @Test
    fun `only web urls leave the overlay`() {
        assertEquals(
            BridgeAction.OpenUrl("https://example.com"),
            IslandBridgeCommands.plan("open_url", """{"url":"https://example.com"}""")
        )
        for (url in listOf(
            "javascript:alert(1)",
            "intent://scan#Intent;scheme=zxing;end",
            "file:///data/data/com.coucou.android/databases/x",
            "content://media/external/audio",
            ""
        )) {
            assertEquals(
                "must refuse $url",
                BridgeAction.ReplyNull,
                IslandBridgeCommands.plan("open_url", Json.write(mapOf("url" to url)))
            )
        }
    }

    // endregion

    // region asset paths

    @Test
    fun `the document request maps to the bundle root, not a doubled folder`() {
        // The page is loaded FROM assets/coucou/ and requests its bundle at the origin
        // root. Failing to strip the prefix here 404s the main document, i.e. no page at
        // all — this is the check that was missed the first time round.
        assertEquals("index.html", IslandBridgeCommands.assetPathFor("coucou/index.html", "coucou"))
        assertEquals("index.html", IslandBridgeCommands.assetPathFor("/coucou/index.html", "coucou"))
    }

    @Test
    fun `root-absolute bundle urls are left alone`() {
        assertEquals("tauri-shim.js", IslandBridgeCommands.assetPathFor("tauri-shim.js", "coucou"))
        assertEquals(
            "assets/island-abc123.js",
            IslandBridgeCommands.assetPathFor("assets/island-abc123.js", "coucou")
        )
    }

    @Test
    fun `a bare directory request serves the document`() {
        assertEquals("index.html", IslandBridgeCommands.assetPathFor("", "coucou"))
        assertEquals("index.html", IslandBridgeCommands.assetPathFor("/", "coucou"))
        assertEquals("index.html", IslandBridgeCommands.assetPathFor("coucou/", "coucou"))
    }

    @Test
    fun `a folder that merely starts like the prefix is not stripped`() {
        assertEquals("coucoufoo/x", IslandBridgeCommands.assetPathFor("coucoufoo/x", "coucou"))
    }

    @Test
    fun `every url the shipped document requests resolves inside the bundle`() {
        // Mirrors what index.html asks for; a mismatch here is a silent 404 on boot.
        // Gradle runs unit tests with the module directory as the working directory.
        val root = "src/main/assets/coucou"
        val document = java.io.File(root, "index.html").readText()
        val urls = Regex("(?:src|href)=\"(/[^\"]+)\"").findAll(document)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("the document should request its bundle", urls.isNotEmpty())
        for (url in urls) {
            val file = java.io.File(root, url)
            assertTrue("$url is requested but not staged", file.exists())
        }
        // And the document itself, which is the one that used to 404.
        assertTrue(java.io.File(root, "index.html").exists())
    }

    // endregion

    // region unknown commands

    @Test
    fun `desktop-only commands answer null instead of failing`() {
        // hooks_*, ingest_file, secret_*, refresh_integration, open_n8n are the Claude
        // Code relay, file drop and integration pollers. Upstream treats a rejection as
        // null, so a null answer is the honest "not on this platform" reply.
        for (command in listOf(
            "hooks_status",
            "hooks_preview",
            "hooks_apply",
            "approval_decision",
            "ingest_file",
            "secret_set",
            "refresh_integration",
            "open_n8n",
            "set_paused",
            "plugin:webview|get_all_webviews"
        )) {
            assertEquals(
                "must not answer $command",
                BridgeAction.ReplyNull,
                IslandBridgeCommands.plan(command, "{}")
            )
        }
    }

    @Test
    fun `commands that are not JSON still answer null rather than throwing`() {
        // boot takes no arguments, so junk there is harmless; the ones that read args
        // must not throw on a payload the shim could not encode.
        assertEquals(BridgeAction.Boot, IslandBridgeCommands.plan("boot", "not json"))
        assertEquals(BridgeAction.ReplyNull, IslandBridgeCommands.plan("set_island_rect", "{{{"))
        assertEquals(BridgeAction.SetCollapsed(false), IslandBridgeCommands.plan("set_collapsed", ""))
    }

    @Test
    fun `envelopes are the shape the js shim expects`() {
        val ok = Json.parseObject(IslandBridgeCommands.envelope("null"))
        assertEquals(true, ok["ok"])
        assertEquals(true, ok.containsKey("value"))

        val failed = Json.parseObject(IslandBridgeCommands.errorEnvelope("nope: \"quoted\""))
        assertEquals(false, failed["ok"])
        assertEquals("nope: \"quoted\"", failed["error"])
    }

    // endregion
}