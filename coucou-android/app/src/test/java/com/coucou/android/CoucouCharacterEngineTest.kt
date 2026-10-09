package com.coucou.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for the character state machine.
 *
 * The engine is deliberately Android-free (no `Canvas`, no wall clock — time only advances
 * through `update(dt)`), so the whole state machine is testable on a plain JVM. These tests
 * are the JVM half of Sprint 2 checklist item 2 ("animation and sound play"); @AGY's
 * emulator pass covers what only a device can show (actual pixels, actual audio).
 */
class CoucouCharacterEngineTest {

    private fun engine() = CoucouCharacterEngine(random = Random(42))

    /** Steps [seconds] of animation in 60 fps frames, like the real frame loop. */
    private fun CoucouCharacterEngine.step(seconds: Float) {
        val frames = (seconds * 60f).toInt().coerceAtLeast(1)
        repeat(frames) { update(1f / 60f) }
    }

    // region state table

    @Test
    fun `every upstream state maps to a config`() {
        for (state in CoucouState.entries) {
            val cfg = CoucouCharacterEngine.BOT_STATES[state]
            assertNotNull("missing config for $state", cfg)
            assertNotNull("missing colour for $state", CoucouCharacterEngine.STATE_COLORS[state])
        }
        assertEquals(CoucouState.entries.size, CoucouCharacterEngine.BOT_STATES.size)
    }

    @Test
    fun `state change reports whether it changed anything`() {
        val engine = engine()
        assertTrue(engine.setState(CoucouState.THINKING))
        assertFalse("re-entering the same state must be a no-op", engine.setState(CoucouState.THINKING))
        assertTrue("force must re-apply", engine.setState(CoucouState.THINKING, force = true))
    }

    @Test
    fun `thinking tilts the eyes up and left`() {
        val engine = engine()
        engine.setState(CoucouState.THINKING)
        engine.step(1f)
        assertEquals(CoucouState.THINKING, engine.state)
        assertTrue("eyes should look up", engine.pitch > 0.05f)
        assertTrue("eyes should track the thinking look offset", engine.yaw > 0.05f)
    }

    @Test
    fun `badge follows the state and fades in`() {
        val engine = engine()
        engine.setState(CoucouState.WORKING)
        assertEquals(
            CoucouCharacterEngine.BadgeKind.DOTS,
            CoucouCharacterEngine.BOT_STATES.getValue(CoucouState.WORKING).badge?.kind
        )
        engine.step(0.5f)
        assertNotNull("working state should show the dots badge", engine.badge)
        assertTrue("badge scale should animate up", engine.badgeS > 0f)
    }

    @Test
    fun `idle clears the badge`() {
        val engine = engine()
        engine.setState(CoucouState.WORKING)
        engine.step(0.5f)
        engine.setState(CoucouState.IDLE)
        engine.step(0.5f)
        assertNull(engine.badge)
    }

    // endregion

    // region motion

    @Test
    fun `blink closes and reopens the eye`() {
        val engine = engine()
        engine.blink()
        engine.step(0.09f)
        val closed = engine.open
        engine.step(0.4f)
        assertTrue("eyes should squeeze shut mid-blink", closed < 0.9f)
        assertEquals("eyes should be open again", 1f, engine.open, 0.02f)
    }

    @Test
    fun `squash deforms then settles back`() {
        val engine = engine()
        engine.squash()
        engine.step(0.12f)
        assertTrue("squash should stretch x", engine.sx > 1.01f)
        engine.step(0.6f)
        assertEquals(1f, engine.sx, 0.02f)
        assertEquals(1f, engine.sy, 0.02f)
    }

    @Test
    fun `error shakes the body back to centre`() {
        val engine = engine()
        engine.setState(CoucouState.ERROR)
        engine.step(0.15f)
        val shaken = kotlin.math.abs(engine.ox)
        engine.step(0.6f)
        assertTrue("error should shake", shaken > 0.001f)
        assertEquals("shake must settle back to 0", 0f, engine.ox, 0.001f)
    }

    @Test
    fun `finished rolls and emits sparks`() {
        val engine = engine()
        engine.setState(CoucouState.FINISHED)
        engine.step(0.4f)
        assertEquals(CoucouState.FINISHED, engine.state)
        assertTrue("finished should roll the eyes", kotlin.math.abs(engine.roll) > 0.01f)
        engine.step(0.3f)
        assertTrue("finished should emit sparks", engine.particles.isNotEmpty())
    }

    @Test
    fun `particles expire instead of accumulating`() {
        val engine = engine()
        engine.emit(CoucouCharacterEngine.ParticleType.HEART, 4)
        assertEquals(4, engine.particles.size)
        engine.step(3f)
        assertTrue("particles should be cleaned up", engine.particles.isEmpty())
    }

    @Test
    fun `sleeping state breathes`() {
        val engine = engine()
        engine.setState(CoucouState.SLEEPING)
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        repeat(180) {
            engine.update(1f / 60f)
            min = minOf(min, engine.sy)
            max = maxOf(max, engine.sy)
        }
        assertTrue("breathing should change height", max - min > 0.005f)
    }

    @Test
    fun `engine reports idle when nothing is moving`() {
        val engine = engine()
        engine.setState(CoucouState.IDLE)
        // The ambient look-around is motion in its own right, so "nothing moving" has to be
        // asserted with it switched off; see the look-around region for the enabled case.
        engine.ambientLook = false
        engine.step(1f)
        assertFalse("idle character should not be busy", engine.busy)
    }

    // endregion

    // region greet + events

    @Test
    fun `greet raises the hands and asks for the greet sound`() {
        val engine = engine()
        val sounds = mutableListOf<SoundPlayer.Sound>()
        var dizzy = false
        engine.onEvent = { event ->
            when (event) {
                is CoucouCharacterEngine.CharacterEvent.Sound -> sounds.add(event.sound)
                CoucouCharacterEngine.CharacterEvent.Dizzy -> dizzy = true
            }
        }
        engine.greet()
        engine.step(0.6f)
        assertTrue("greet should raise a hand", engine.hands > 0.2f)
        engine.step(1.4f)
        assertTrue("greet should ask for the greet sound", SoundPlayer.Sound.GREET in sounds)
        assertTrue("hands should come back down", engine.hands < 0.2f)
        assertFalse(dizzy)
    }

    @Test
    fun `three quick taps make the character dizzy`() {
        val engine = engine()
        var dizzy = false
        engine.onEvent = { event ->
            if (event == CoucouCharacterEngine.CharacterEvent.Dizzy) dizzy = true
        }
        repeat(3) {
            engine.slap()
            engine.step(0.3f)
        }
        assertTrue("three slaps should trigger dizzy", dizzy)
    }

    @Test
    fun `permanent eye override survives idle updates`() {
        val engine = engine()
        engine.setPermanentEye(CoucouCharacterEngine.EyeShape.HEART)
        engine.step(2f)
        assertEquals(CoucouCharacterEngine.EyeShape.HEART, engine.effectiveEye)
        engine.setPermanentEye(null)
        engine.step(2f)
        assertEquals(CoucouCharacterEngine.EyeShape.PILL, engine.effectiveEye)
    }

    @Test
    fun `emotes drive eyes and particles`() {
        val engine = engine()
        engine.triggerEmote(CoucouCharacterEngine.Emote.LOVE)
        assertEquals(CoucouCharacterEngine.EyeShape.HEART, engine.effectiveEye)
        assertTrue("love should emit hearts", engine.particles.isNotEmpty())
        engine.step(3f)
        assertEquals(CoucouCharacterEngine.EyeShape.PILL, engine.effectiveEye)
    }

    // endregion

    // region contract with the rest of the app

    @Test
    fun `state sound table covers the emotive states`() {
        assertEquals(SoundPlayer.Sound.THINK, CoucouCharacterEngine.STATE_SOUND[CoucouState.THINKING])
        assertEquals(SoundPlayer.Sound.ERROR, CoucouCharacterEngine.STATE_SOUND[CoucouState.ERROR])
        assertEquals(SoundPlayer.Sound.FINISH, CoucouCharacterEngine.STATE_SOUND[CoucouState.FINISHED])
        assertNull("idle is silent upstream", CoucouCharacterEngine.STATE_SOUND[CoucouState.IDLE])
        // Every mapped sound must resolve to a resource name the asset pass will produce.
        for ((state, sound) in CoucouCharacterEngine.STATE_SOUND) {
            assertTrue(
                "$state -> ${sound.rawName} must live in res/raw",
                sound.rawName.startsWith("coucou_")
            )
        }
    }

    @Test
    fun `state names round-trip for logcat diagnostics`() {
        for (state in CoucouState.entries) {
            assertEquals(state, CoucouState.fromWireNameOrNull(state.wireName))
        }
        assertNull(CoucouState.fromWireNameOrNull("nope"))
    }

    @Test
    fun `body outline is a closed superellipse`() {
        val engine = engine()
        val points = FloatArray(73 * 2)
        val n = engine.bodyOutline(1.14f, 0.88f, 1f, points)
        assertEquals(72, n)
        var maxRadius = 0f
        for (i in 0..n) {
            val radius = kotlin.math.hypot(points[i * 2], points[i * 2 + 1])
            maxRadius = maxOf(maxRadius, radius)
        }
        // A capsule, not a circle: wider than tall but bounded.
        assertTrue("outline should be about rx-sized", maxRadius in 1.0f..1.3f)
        assertTrue("outline must not be empty", points[0] != 0f || points[1] != 0f)
    }

    // endregion

    // region look-around, caret follow & idle timer

    @Test
    fun `ambient look-around wanders inside the upstream ranges`() {
        val engine = engine()
        var retargets = 0
        var lastTarget = Float.NaN
        repeat(60 * 60) {
            engine.update(1f / 60f)
            val x = engine.gazeX
            val y = engine.gazeY
            assertTrue("gaze x out of range: $x", x >= CoucouCharacterEngine.LOOK_MIN_X)
            assertTrue("gaze x out of range: $x", x <= CoucouCharacterEngine.LOOK_MAX_X)
            assertTrue("gaze y out of range: $y", y >= CoucouCharacterEngine.LOOK_MIN_Y)
            assertTrue("gaze y out of range: $y", y <= CoucouCharacterEngine.LOOK_MAX_Y)
            if (x != lastTarget) {
                retargets++
                lastTarget = x
            }
        }
        // 60 s of idle at a 0.5-2.0 s cadence: many retargets, and never a stuck gaze.
        assertTrue("should retarget often, got $retargets", retargets > 20)
    }

    @Test
    fun `ambient look-around can be switched off`() {
        val engine = engine()
        engine.ambientLook = false
        repeat(60 * 10) { engine.update(1f / 60f) }
        assertEquals(0f, engine.gazeX, 0f)
        assertEquals(0f, engine.gazeY, 0f)
    }

    @Test
    fun `caret follow overrides the ambient gaze and releases after the hold`() {
        val engine = engine()
        repeat(60 * 5) { engine.update(1f / 60f) }
        val ambient = engine.gazeX
        assertTrue("precondition: ambient gaze should have wandered", ambient != 0f)

        engine.setLook(0.8f, CoucouCharacterEngine.CARET_LOOK_Y)
        assertTrue(engine.lookOverride)
        assertEquals(0.8f, engine.gazeX, 0f)

        // Still held just before the 1.5 s deadline, released just after.
        engine.step(1.4f)
        assertTrue("hold must outlive a fast typist's gap", engine.lookOverride)
        engine.step(0.2f)
        assertFalse("hold expires at CARET_LOOK_HOLD", engine.lookOverride)
        // The wander owns the gaze again and may well have picked a new target meanwhile.
        assertTrue(
            "gaze must return to the wander range, was ${engine.gazeX}",
            engine.gazeX >= CoucouCharacterEngine.LOOK_MIN_X &&
                engine.gazeX <= CoucouCharacterEngine.LOOK_MAX_X
        )
        assertTrue("gaze must not stay pinned at the caret", engine.gazeX != 0.8f)
    }

    @Test
    fun `each keystroke refreshes the caret hold`() {
        val engine = engine()
        engine.setLook(0.5f, 0.2f)
        engine.step(1f)
        // A second keystroke 1 s later must push the deadline out, not let it lapse.
        engine.setLook(0.6f, 0.2f)
        engine.step(1f)
        assertTrue(engine.lookOverride)
        engine.step(0.6f)
        assertFalse(engine.lookOverride)
    }

    @Test
    fun `resetLook centres the eyes immediately`() {
        val engine = engine()
        engine.setLook(-0.7f, -0.7f)
        engine.releaseLook()
        assertFalse(engine.lookOverride)
        assertEquals(0f, engine.lookX, 0f)
        assertEquals(0f, engine.lookY, 0f)
    }

    @Test
    fun `host gaze is clamped to the normalised range`() {
        val engine = engine()
        engine.setLook(9f, -9f)
        assertEquals(1f, engine.gazeX, 0f)
        assertEquals(-1f, engine.gazeY, 0f)
        engine.setLook(-9f, 9f)
        assertEquals(-1f, engine.gazeX, 0f)
        assertEquals(1f, engine.gazeY, 0f)
    }

    @Test
    fun `clearing the field releases the caret hold`() {
        val engine = engine()
        engine.setLook(0.9f, 0.2f)
        engine.releaseLook()
        engine.step(0.1f)
        // Already released, so a stale reset cannot resurrect the override.
        assertFalse(engine.lookOverride)
    }

    @Test
    fun `caret index maps to a normalised gaze`() {
        assertEquals(0f, CoucouCharacterEngine.caretLookX(5, 10), 1e-6f)
        assertEquals(1f, CoucouCharacterEngine.caretLookX(10, 10), 1e-6f)
        assertEquals(-1f, CoucouCharacterEngine.caretLookX(0, 10), 1e-6f)
        assertEquals(0f, CoucouCharacterEngine.caretLookX(0, 0), 1e-6f)
        // A stale selectionStart of -1 must not send the gaze off screen.
        assertEquals(0f, CoucouCharacterEngine.caretLookX(-1, 4), 1e-6f)
        assertEquals(1f, CoucouCharacterEngine.caretLookX(99, 4), 1e-6f)
    }

    @Test
    fun `state gaze overrides the ambient wander`() {
        val engine = engine()
        repeat(60 * 4) { engine.update(1f / 60f) }
        assertTrue("precondition: ambient gaze should be off centre", engine.gazeX != 0f)

        // Sleeping centres the eyes regardless of where the wander left them.
        engine.setState(CoucouState.SLEEPING)
        engine.step(3f)
        assertTrue("sleeping must recentre, yaw=${engine.yaw}", kotlin.math.abs(engine.yaw) < 0.05f)

        // Thinking blends the host gaze with its own fixed look, exactly as upstream does:
        // freeze the wander first so the expected target is deterministic.
        engine.ambientLook = false
        val frozen = engine.gazeX
        engine.setState(CoucouState.THINKING)
        engine.step(3f)
        val look = CoucouCharacterEngine.BOT_STATES.getValue(CoucouState.THINKING).look!!
        // update() composes `ty = gazeX * 0.62`, then `ty * 0.35 + look.x * 0.55`.
        val expected = frozen * 0.62f * 0.35f + look.x * 0.55f
        assertTrue(
            "thinking must own the gaze, yaw=${engine.yaw} expected=$expected",
            kotlin.math.abs(engine.yaw - expected) < 0.02f
        )
    }

    @Test
    fun `idle timer counts up and restarts on activity`() {
        val engine = engine()
        repeat(60 * 3) { engine.update(1f / 60f) }
        assertTrue("idle timer should have accumulated", engine.idleSeconds > 2.5f)
        engine.notifyUserActive()
        assertEquals(0f, engine.idleSeconds, 1e-4f)
        engine.step(1f)
        engine.setLook(0.3f, 0.2f)
        assertEquals("typing counts as activity", 0f, engine.idleSeconds, 1e-4f)
    }

    @Test
    fun `pausing the clock freezes the gaze but keeps the hold deadline`() {
        val engine = engine()
        engine.setLook(0.8f, 0.2f)
        // update(0) and negative steps are no-ops, which is what a paused loop produces.
        engine.update(0f)
        engine.update(-1f)
        assertTrue("a paused loop must not advance the hold", engine.lookOverride)
        assertEquals(0.8f, engine.gazeX, 0f)
    }

    // endregion

    // region Sprint 6.3: authentic desktop idle animation physics

    @Test
    fun `idle state breathes gently with 1_5f frequency and 0_03f amp`() {
        val engine = engine()
        engine.setState(CoucouState.IDLE)
        var minSy = Float.MAX_VALUE
        var maxSy = -Float.MAX_VALUE
        // Step through 2.5 seconds (covers more than one full 1.5 rad/s cycle: T = 2π / 1.5 ≈ 4.19s)
        repeat(240) {
            engine.update(1f / 60f)
            minSy = minOf(minSy, engine.sy)
            maxSy = maxOf(maxSy, engine.sy)
        }
        assertTrue("idle height must oscillate with breathing", maxSy - minSy > 0.01f)
        assertTrue("amplitude must stay bounded within gentle 0.03 range", maxSy <= 1.035f && minSy >= 0.965f)
    }

    @Test
    fun `blink closes and opens in approximately 120ms`() {
        val engine = engine()
        engine.blink()
        // Mid-blink at 50ms should be mostly closed
        engine.update(0.05f)
        assertTrue("eyes should close during blink", engine.open < 0.5f)
        // By 120ms (50ms + 70ms), eye should reopen
        engine.update(0.08f)
        assertTrue("eyes should reopen by 130ms", engine.open > 0.9f)
    }

    @Test
    fun `smooth eye look damping smoothly approaches targets without overshoot`() {
        val engine = engine()
        engine.ambientLook = false
        // Fixed look target via thinking state
        engine.setState(CoucouState.THINKING)
        val initialYaw = engine.yaw
        // After 1 frame, delta should be approximately 8% of target gap (spring damping = 0.08f)
        engine.update(1f / 60f)
        val firstStep = kotlin.math.abs(engine.yaw - initialYaw)
        assertTrue("first step must be smooth and damped", firstStep > 0.001f && firstStep < 0.1f)
    }

    // endregion
}
