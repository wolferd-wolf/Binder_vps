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
    fun `boot payload reports the screen in dp, because that is what the page lays out in`() {
        // A 1080x2340 px screen at 420dpi (density 2.625) is 411.4dp wide. Reporting the
        // px instead is what made the panel ~2.6x too wide on the device.
        val screen = IslandBridgeCommands.Screen(1080, 2340, 2.625f)
        val boot = Json.parseObject(IslandBridgeCommands.bootJson(IslandSettings(), screen))
        val reported = boot["screen"] as Map<*, *>
        assertEquals(411.43, reported["width"] as Double, 0.01)
        assertEquals(891.43, reported["height"] as Double, 0.01)
        assertEquals(2.625, reported["scale"] as Double, 1e-9)
    }

    @Test
    fun `boot reports dp even when the density is not a round number`() {
        // 720x1280 at 320dpi: exactly 360dp, the emulator Boss tests on.
        val boot = Json.parseObject(
            IslandBridgeCommands.bootJson(IslandSettings(), IslandBridgeCommands.Screen(720, 1280, 2f))
        )
        val reported = boot["screen"] as Map<*, *>
        assertEquals(360.0, reported["width"] as Double, 0.001)
        assertEquals(640.0, reported["height"] as Double, 0.001)
        assertEquals(2.0, reported["scale"] as Double, 1e-9)
    }

    @Test
    fun `a broken density reports dp rather than dividing by zero`() {
        val screen = IslandBridgeCommands.Screen(1080, 2340, 0f)
        assertEquals(1.0, screen.scale, 1e-9)
        assertEquals(1080.0, screen.widthDp, 0.01)
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
    fun `window bounds are the screen minus the panel margin`() {
        // 720px wide at density 2 (360dp): a 16dp margin each side leaves 688px.
        val bounds = IslandBridgeCommands.windowBounds(160.0, 2f, 720)

        assertEquals(720 - 2 * 16 * 2, bounds[0])
        // Offset by the same margin, so the panel's own centring lands on screen centre.
        assertEquals(16 * 2, bounds[2])
        assertTrue("must never be wider than the screen", bounds[0] < 720)
    }

    @Test
    fun `window height follows the island, scaled from dp to px`() {
        // A 160dp-tall island at density 2.625 is 420px, plus the margin on each side.
        val bounds = IslandBridgeCommands.windowBounds(160.0, 2.625f, 1080)
        assertEquals((160 * 2.625).toInt() + 2 * (16 * 2.625).toInt(), bounds[1])
    }

    @Test
    fun `window bounds track the island height, not just the panel width`() {
        val compact = IslandBridgeCommands.windowBounds(32.0, 2f, 720)
        val expanded = IslandBridgeCommands.windowBounds(240.0, 2f, 720)
        // The panel is the screen either way; only the height follows the island.
        assertEquals(compact[0], expanded[0])
        assertTrue(compact[1] < expanded[1])
    }

    @Test
    fun `collapsed window is the wake strip, narrow and centred`() {
        val bounds = IslandBridgeCommands.collapsedWindowBounds(2f, 720)
        assertEquals((240 + 16) * 2, bounds[0])
        // Narrow and centred, so the sleeping island never eats a whole screen row.
        assertTrue("must not span the screen row", bounds[0] < 720)
        assertEquals((720 - bounds[0]) / 2, bounds[2])
        assertTrue("a 6dp island still needs room to be tapped", bounds[1] > 0)
    }

    @Test
    fun `collapsed window cannot outgrow a narrow screen`() {
        // A 240dp strip on a 300px screen: capped to the panel, never past it.
        val bounds = IslandBridgeCommands.collapsedWindowBounds(3f, 300)
        assertTrue(bounds[0] <= 300)
        assertEquals((300 - bounds[0]) / 2, bounds[2])
    }

    @Test
    fun `window bounds survive a degenerate screen width and density`() {
        val bounds = IslandBridgeCommands.windowBounds(160.0, 0f, 0)
        assertTrue(bounds.all { it >= 0 })
        assertTrue(bounds[0] > 0 && bounds[1] > 0)

        val collapsed = IslandBridgeCommands.collapsedWindowBounds(0f, 0)
        assertTrue(collapsed.all { it >= 0 })
        assertTrue(collapsed[0] > 0 && collapsed[1] > 0)
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

    @Test
    fun `the shipped document keeps the device-width viewport, so a css px is a dp`() {
        // The window is the screen less its margins and `boot` reports the screen in dp,
        // so the whole geometry chain only lines up while the WebView lays out 1 CSS px
        // per dp. Pinning the viewport to the 720px desktop stage broke that: the WebView
        // squeezed 720 CSS px into the window and the island's own 12.5px type rendered
        // at ~6dp. A regression here is invisible on a desktop browser, hence the test.
        val root = "src/main/assets/coucou"
        val viewport = requireNotNull(
            Regex("""<meta name="viewport"[^>]*>""")
                .find(java.io.File(root, "index.html").readText())
                ?.value
        ) { "the document has no viewport meta" }
        assertTrue("viewport must not be pinned to the 720px stage: $viewport", !viewport.contains("width=720"))
        assertTrue("viewport must follow the device: $viewport", viewport.contains("device-width"))
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