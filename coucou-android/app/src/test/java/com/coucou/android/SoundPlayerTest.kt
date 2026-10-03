package com.coucou.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies [SoundPlayer] degrades safely while the sound assets are still on hold.
 *
 * The point of the name-based lookup is that `play()` is a no-op rather than a crash or a
 * compile error before `res/raw/coucou_*` exists. These tests pin that contract so it
 * cannot silently regress once the assets land.
 */
class SoundPlayerTest {

    // NOTE: the runtime behaviour (play() no-op when res/raw/coucou_* is absent) needs a
    // Context, which requires androidx.test.core. That dependency is not declared and
    // app/build.gradle belongs to @AGY, so it is not asserted here rather than editing a
    // peer's build file. @AGY's emulator pass covers it.

    @Test
    fun `sound enum maps to the raw names reserved on the board`() {
        assertEquals("coucou_greet", SoundPlayer.Sound.GREET.rawName)
        assertEquals("coucou_open", SoundPlayer.Sound.OPEN.rawName)
        assertEquals("coucou_pop", SoundPlayer.Sound.POP.rawName)
        assertEquals("coucou_blip", SoundPlayer.Sound.BLIP.rawName)
        assertEquals("coucou_send", SoundPlayer.Sound.SEND.rawName)
        assertEquals("coucou_error", SoundPlayer.Sound.ERROR.rawName)
    }

    @Test
    fun `unknown sound name resolves to null`() {
        assertNull(SoundPlayer.Sound.fromNameOrNull("coucou_nope"))
        assertEquals(SoundPlayer.Sound.OPEN, SoundPlayer.Sound.fromNameOrNull("OPEN"))
    }
}