package com.coucou.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Device-independent tests for [CommandRouter.parse].
 *
 * These cover the pure parsing contract only. App resolution and window behaviour
 * are verified on-device by @AGY's emulator QA pass.
 */
class CommandRouterParseTest {

    @Test
    fun `bare app name becomes the argument`() {
        val command = CommandRouter.parse("chrome")
        assertEquals("chrome", command.raw)
        assertNull(command.verb)
        assertEquals("chrome", command.argument)
    }

    @Test
    fun `open verb is stripped from the argument`() {
        val command = CommandRouter.parse("open chrome")
        assertEquals("open", command.verb)
        assertEquals("chrome", command.argument)
    }

    @Test
    fun `verb matching is case insensitive`() {
        val command = CommandRouter.parse("OPEN Chrome")
        assertEquals("open", command.verb)
        assertEquals("Chrome", command.argument)
    }

    @Test
    fun `other known verbs are stripped too`() {
        listOf("launch", "start", "run", "go").forEach { verb ->
            val command = CommandRouter.parse("$verb maps")
            assertEquals(verb, command.verb)
            assertEquals("maps", command.argument)
        }
    }

    @Test
    fun `multi word argument is preserved`() {
        val command = CommandRouter.parse("open google maps")
        assertEquals("open", command.verb)
        assertEquals("google maps", command.argument)
    }

    @Test
    fun `unknown first word is treated as part of the query`() {
        val command = CommandRouter.parse("please chrome")
        assertNull(command.verb)
        assertEquals("please chrome", command.argument)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val command = CommandRouter.parse("   chrome   ")
        assertEquals("chrome", command.argument)
    }

    @Test
    fun `empty input yields a null argument`() {
        assertNull(CommandRouter.parse("").argument)
        assertNull(CommandRouter.parse("    ").argument)
    }

    @Test
    fun `bare verb with trailing space is just the trimmed word`() {
        // Trimming makes "open " indistinguishable from a bare "open", so it is
        // treated as an app-name query rather than as a verb with a missing target.
        val command = CommandRouter.parse("open ")
        assertNull(command.verb)
        assertEquals("open", command.argument)
    }

    @Test
    fun `blank argument routes as unknown when nothing handles it`() {
        val router = DefaultCommandRouter(emptyList())
        // raw is trimmed, so a blank input reports an empty query.
        assertEquals(CommandResult.Unknown(""), router.route(CommandRouter.parse("   ")))
        assertEquals(CommandResult.Unknown("open"), router.route(CommandRouter.parse("open ")))
    }

    @Test
    fun `router reports unknown when no handler claims the command`() {
        val router = DefaultCommandRouter(emptyList())
        val result = router.route("chrome")
        assertEquals(CommandResult.Unknown("chrome"), result)
    }
}