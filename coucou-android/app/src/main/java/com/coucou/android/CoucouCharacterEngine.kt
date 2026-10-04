package com.coucou.android

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The character's lifecycle, as seen by the overlay.
 *
 * Names (and the "wire name" derived from each) mirror `BOT_STATES` in
 * `coucou/windows/src/mochi/engine.ts` so the state → colour, eye, badge and sound
 * tables stay one-to-one with upstream.
 */
enum class CoucouState(val wireName: String) {
    IDLE("idle"),
    WORKING("working"),
    THINKING("thinking"),
    SEARCHING("searching"),
    APPROVAL("approval"),
    QUESTION("question"),
    ERROR("error"),
    FINISHED("finished"),
    RATELIMIT("ratelimit"),
    SLEEPING("sleeping"),
    DIZZY("dizzy");

    companion object {
        fun fromWireNameOrNull(name: String): CoucouState? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/**
 * Pure-Kotlin port of the procedural character motion/state maths.
 *
 * Source of truth: `coucou/windows/src/mochi/engine.ts` (Canvas 2D port of
 * `NotchBuddy/Sources/App/BotEngine.swift`), itself MIT-licensed — see upstream
 * `LICENSE` / `LICENSE-ASSETS.md`. Only the *mechanics* are ported: the palette is
 * injected ([CoucouPalette]) and ultimately comes from @Buffy's theme tokens, because
 * upstream reserves the Mochi design itself (see upstream `LICENSE-ASSETS.md`).
 *
 * Two deliberate deviations from the TypeScript original, both in service of
 * testability (JVM unit tests, no Robolectric):
 *
 *  1. **No wall clock.** Upstream reads `performance.now()` inside `update()` and inside
 *     every tween. Here time only advances through [update], so a test can step the
 *     whole animation deterministically. Same maths, same result at a fixed `dt`.
 *  2. **No `setTimeout`.** Upstream schedules follow-ups with timers (`emit("spark", 5)`
 *     500 ms after `finished`, the greet wave ramp). Here they sit on an internal queue
 *     ticked by [update], so nothing fires after the engine goes idle.
 *
 * This class has no Android imports on purpose: [CoucouCharacterView] owns all the
 * `Canvas` work, which keeps the state machine unit-testable on a plain JVM.
 */
class CoucouCharacterEngine(
    private val random: Random = Random.Default,
    val palette: CoucouPalette = CoucouPalette()
) {

    // region geometry constants (MochiConst / PISTES.mochi)

    /** Where the host is pointing the eyes, normalised to -1..1. Ignored unless [lookOverride]. */
    var lookX = 0f
        private set

    /** Vertical half of the host-driven gaze; negative is up, as on the Canvas y axis. */
    var lookY = 0f
        private set

    /**
     * True while a host-driven target (caret follow) owns the gaze.
     *
     * Upstream's `miniLookTarget` only wanders when nothing else is pointing the character,
     * so the override — not a separate code path — is what keeps ambient look-around from
     * fighting the caret.
     */
    var lookOverride: Boolean = false
        private set

    /** Seconds since the last host interaction; the idle timer QA reads. */
    val idleSeconds: Float get() = time - lastInputAt

    /** Current gaze, whether it comes from the host or from the ambient wander. */
    val gazeX: Float get() = if (lookOverride) lookX else ambientLookX
    val gazeY: Float get() = if (lookOverride) lookY else ambientLookY

    /** Lets the host switch the ambient look-around off entirely (e.g. collapsed bubble). */
    var ambientLook: Boolean = true

    /** Superellipse outline of the body, sampled the way upstream's `bodyPath` is. */
    fun bodyOutline(rx: Float, ry: Float, r: Float, out: FloatArray): Int {
        val n = OUTLINE_POINTS
        val m = morph
        for (i in 0..n) {
            val a = (i.toFloat() / n) * TWO_PI
            val ca = cos(a)
            val sa = sin(a)
            var px = rx * superSign(ca, BODY_EXPONENT)
            var py = ry * superSign(sa, BODY_EXPONENT)
            if (m >= 0.005f) {
                val rr = rrPoint(ca, sa, r * 1.0f, r * 0.94f, r * 0.42f)
                px += (rr[0] - px) * m
                py += (rr[1] - py) * m
            }
            out[i * 2] = px
            out[i * 2 + 1] = py
        }
        return n
    }

    // endregion

    // region animated state (upstream `BotEngine.s`)

    var yaw = 0f
        private set
    var pitch = 0f
        private set
    var roll = 0f
        private set
    var tilt = 0f
        private set
    var open = 1f
        private set
    var sx = 1f
        private set
    var sy = 1f
        private set
    var oy = 0f
        private set
    var ox = 0f
        private set
    var tint = 0f
        private set
    var blush = 0f
        private set
    var hands = 0f
        private set
    var morph = 0f
        private set
    var es = 1f
        private set
    var badgeS = 0f
        private set

    var col = palette.stateColor(CoucouState.IDLE)
        private set
    var colT = col
        private set

    var state = CoucouState.IDLE
        private set
    var cfg = BOT_STATES.getValue(CoucouState.IDLE)
        private set

    var eyeOverride: EyeShape? = null
        private set
    var badge: Badge? = null
        private set

    /** Seconds since construction; also the phase reference for all idle motion. */
    var time = random.nextFloat() * 5f
        private set

    var waveStart = 0f
        private set
    var waveUntil = 0f
        private set

    val particles = mutableListOf<Particle>()

    /** Sounds/behaviour the character itself asks for, as opposed to UI sounds. */
    var onEvent: ((CharacterEvent) -> Unit)? = null

    private var eyeOverrideUntil = 0f
    private var permanentEye: EyeShape? = null
    private var badgeToken = 0
    private var badgeKey = "none"
    private var greetToken = 0
    private var nextBlink = 1.5f + random.nextFloat() * 2f
    private var lastAmbient = 0f
    private var slotH = 0f
    private var slotHTarget = 0f
    private var slotHVel = 0f
    private var chewing = false
    private var slapTimes = mutableListOf<Float>()

    private val tweens = mutableMapOf<Prop, Tween>()
    private val locks = mutableSetOf<Prop>()
    private val scheduled = mutableListOf<Scheduled>()

    private var tgYaw = 0f
    private var tgPitch = 0f
    private var tgTilt = 0f
    private var tgSy = 1f
    private var tgSx = 1f

    // Ambient look-around, a port of upstream's `miniLookTarget` (the menu-bar mini bots,
    // which are the closest upstream analogue of our always-on-screen bubble).
    private var ambientLookX = 0f
    private var ambientLookY = 0f
    private var nextLookAt = LOOK_FIRST_DELAY + random.nextFloat() * LOOK_DELAY_SPREAD
    private var lookOverrideUntil = 0f
    private var lastInputAt = 0f

    // endregion

    // region public API

    /** @return true when the state actually changed. */
    fun setState(next: CoucouState, force: Boolean = false): Boolean {
        if (state == next && !force) return false
        val prev = state
        state = next
        cfg = BOT_STATES.getValue(next)
        colT = cfg.color
        if (!locks.contains(Prop.TINT)) tint = cfg.tint
        if (!locks.contains(Prop.TILT)) tgTilt = cfg.tilt
        setBadge(cfg.badge)

        when (next) {
            CoucouState.FINISHED -> {
                doRoll(950f, 1)
                schedule(0.5f) { emit(ParticleType.SPARK, 5) }
            }
            CoucouState.ERROR -> anim(
                Prop.OX,
                listOf(
                    TweenKey(0.08f, 50f, ::easeOut),
                    TweenKey(-0.08f, 70f, ::easeInOut),
                    TweenKey(0.05f, 70f, ::easeInOut),
                    TweenKey(0f, 90f, ::easeOut)
                )
            )
            CoucouState.APPROVAL -> anim(
                Prop.OY,
                listOf(TweenKey(-0.2f, 150f, ::easeOut), TweenKey(0f, 300f, ::easeBack))
            )
            CoucouState.DIZZY -> doRoll(1300f, 2)
            CoucouState.QUESTION -> blink()
            CoucouState.RATELIMIT -> emit(ParticleType.SWEAT, 1)
            else -> if (prev != CoucouState.IDLE || next != CoucouState.IDLE) blink()
        }
        return true
    }

    /**
     * Points the eyes at a host-driven position — the caret-follow entry point.
     *
     * The gaze stays pinned here for [CARET_LOOK_HOLD] seconds so a single keystroke is
     * visible, then [releaseLook] hands it back to the ambient scheduler. Every call also
     * refreshes [idleSeconds], so typing counts as activity.
     */
    fun setLook(x: Float, y: Float) {
        lookX = x.coerceIn(-1f, 1f)
        lookY = y.coerceIn(-1f, 1f)
        lookOverride = true
        lookOverrideUntil = time + CARET_LOOK_HOLD
        lastInputAt = time
    }

    /** Drops the host gaze immediately and centres the eyes. */
    fun releaseLook() {
        lookOverride = false
        lookOverrideUntil = 0f
        lookX = 0f
        lookY = 0f
    }

    /**
     * Records user activity (a tap, a drag, a keystroke) without changing the gaze, and
     * restarts the idle timer. Called by the view whenever the frame loop resumes.
     */
    fun notifyUserActive() {
        lastInputAt = time
    }

    fun setBadge(next: Badge?) {
        val key = next?.let { "${it.kind}-${it.color}" } ?: "none"
        if (key == badgeKey) return
        badgeKey = key
        val token = ++badgeToken
        anim(Prop.BADGE_S, listOf(TweenKey(0f, 90f, ::easeInOut)))
        schedule(0.1f) {
            if (token != badgeToken) return@schedule
            badge = next
            if (next != null) {
                anim(Prop.BADGE_S, listOf(TweenKey(1f, 280f, ::easeBack)))
            }
        }
    }

    fun blink() {
        if (locks.contains(Prop.OPEN)) return
        anim(
            Prop.OPEN,
            listOf(TweenKey(0.06f, 70f, ::easeInOut), TweenKey(1f, 130f, ::easeOut))
        )
    }

    fun squash() {
        anim(
            Prop.SY,
            listOf(
                TweenKey(0.78f, 70f, ::easeOut),
                TweenKey(1.1f, 130f, ::easeOut),
                TweenKey(1f, 170f, ::easeInOut)
            )
        )
        anim(
            Prop.SX,
            listOf(
                TweenKey(1.16f, 70f, ::easeOut),
                TweenKey(0.95f, 130f, ::easeOut),
                TweenKey(1f, 170f, ::easeInOut)
            )
        )
    }

    /** Three quick slaps in a row make the character dizzy. */
    fun slap() {
        interruptGreet()
        if (state == CoucouState.DIZZY) return
        val now = time
        slapTimes = slapTimes.filter { now - it < 1.7f }.toMutableList()
        slapTimes.add(now)
        onEvent?.invoke(CharacterEvent.Sound(SoundPlayer.Sound.SLAP))
        squash()
        if (slapTimes.size >= 3) {
            slapTimes = mutableListOf()
            onEvent?.invoke(CharacterEvent.Dizzy)
        } else {
            eyeOverride = EyeShape.LINE
            eyeOverrideUntil = now + 0.8f
            schedule(0.06f) { onEvent?.invoke(CharacterEvent.Sound(SoundPlayer.Sound.ANNOYED)) }
        }
    }

    fun doRoll(durationMs: Float, turns: Int) {
        roll = 0f
        anim(
            Prop.ROLL,
            listOf(TweenKey(TWO_PI * turns, durationMs, ::easeInOut))
        ) { roll = 0f }
    }

    /** The "coucou" peek wave. Timings ported from `BotEngine.greet()`. */
    fun greet() {
        val token = ++greetToken
        waveStart = time + 0.45f
        waveUntil = time + 1.55f

        eyeOverride = EyeShape.HAPPY
        eyeOverrideUntil = time + 2.0f
        anim(Prop.OY, listOf(TweenKey(-0.06f, 220f, ::easeOut), TweenKey(0f, 220f, ::easeBack)))

        schedule(0.25f) {
            if (greetToken != token) return@schedule
            anim(Prop.HANDS, listOf(TweenKey(1f, 280f, ::easeOut)))
            anim(
                Prop.SY,
                listOf(TweenKey(0.95f, 100f, ::easeOut), TweenKey(1f, 260f, ::easeBack))
            )
            anim(
                Prop.SX,
                listOf(TweenKey(1.04f, 100f, ::easeOut), TweenKey(1f, 260f, ::easeBack))
            )
            onEvent?.invoke(CharacterEvent.Sound(SoundPlayer.Sound.GREET))
        }
        schedule(0.55f) { if (greetToken == token) blink() }
        schedule(1.5f) { if (greetToken == token) blink() }
        schedule(1.55f) {
            if (greetToken != token) return@schedule
            waveUntil = 0f
            anim(Prop.HANDS, listOf(TweenKey(0f, 200f, ::easeInOut)))
        }
        schedule(1.75f) {
            if (greetToken != token) return@schedule
            eyeOverride = EyeShape.HAPPY
            eyeOverrideUntil = time + 0.3f
        }
    }

    fun interruptGreet() {
        if (hands <= 0.01f && time >= waveUntil) return
        greetToken++
        waveUntil = 0f
        waveStart = 0f
        anim(Prop.HANDS, listOf(TweenKey(0f, 150f, ::easeInOut)))
    }

    fun setPermanentEye(shape: EyeShape?) {
        permanentEye = shape
        if (shape != null) {
            eyeOverride = shape
            eyeOverrideUntil = Float.MAX_VALUE
        } else if (eyeOverrideUntil == Float.MAX_VALUE) {
            eyeOverride = null
            eyeOverrideUntil = 0f
        }
    }

    fun triggerEmote(emote: Emote, duration: Float = 1.8f) {
        val start = time
        eyeOverride = emote.eye
        eyeOverrideUntil = start + duration

        when (emote) {
            Emote.LOVE -> {
                anim(
                    Prop.BLUSH,
                    listOf(
                        TweenKey(1f, 300f, ::easeOut),
                        TweenKey(1f, (duration - 0.6f) * 1000f, ::easeLin),
                        TweenKey(0f, 300f, ::easeInOut)
                    )
                )
                emit(ParticleType.HEART, 4)
                anim(
                    Prop.OY,
                    listOf(TweenKey(-0.1f, 160f, ::easeOut), TweenKey(0f, 300f, ::easeBack))
                )
            }
            Emote.SURPRISED -> {
                anim(
                    Prop.OY,
                    listOf(TweenKey(-0.3f, 140f, ::easeOut), TweenKey(0f, 380f, ::easeBack))
                )
                anim(
                    Prop.ES,
                    listOf(TweenKey(1.25f, 120f, ::easeOut), TweenKey(1f, 500f, ::easeInOut))
                )
            }
            Emote.PROUD -> {
                emit(ParticleType.STAR, 5)
                anim(
                    Prop.TILT,
                    listOf(
                        TweenKey(-0.14f, 220f, ::easeOut),
                        TweenKey(-0.14f, (duration - 0.5f) * 1000f, ::easeLin),
                        TweenKey(0f, 280f, ::easeInOut)
                    )
                )
                anim(
                    Prop.BLUSH,
                    listOf(
                        TweenKey(0.7f, 250f, ::easeOut),
                        TweenKey(0.7f, (duration - 0.5f) * 1000f, ::easeLin),
                        TweenKey(0f, 300f, ::easeInOut)
                    )
                )
            }
            Emote.WINK -> anim(
                Prop.TILT,
                listOf(
                    TweenKey(0.12f, 160f, ::easeOut),
                    TweenKey(0.12f, (duration - 0.4f) * 1000f, ::easeLin),
                    TweenKey(0f, 240f, ::easeInOut)
                )
            )
            Emote.YAWN -> {
                anim(
                    Prop.SY,
                    listOf(TweenKey(1.12f, 500f, ::easeInOut), TweenKey(1f, 500f, ::easeInOut))
                )
                anim(
                    Prop.SX,
                    listOf(TweenKey(0.94f, 500f, ::easeInOut), TweenKey(1f, 500f, ::easeInOut))
                )
                schedule(0.7f) {
                    eyeOverride = EyeShape.CLOSED
                    emit(ParticleType.Z, 2)
                }
            }
            Emote.HAPPY -> anim(
                Prop.BLUSH,
                listOf(TweenKey(0.6f, 200f, ::easeOut), TweenKey(0f, 600f, ::easeInOut))
            )
            Emote.ANNOYED -> {
                eyeOverride = EyeShape.LINE
                eyeOverrideUntil = start + 0.8f
                schedule(0.06f) { onEvent?.invoke(CharacterEvent.Sound(SoundPlayer.Sound.ANNOYED)) }
            }
        }
    }

    fun emit(type: ParticleType, count: Int) {
        repeat(count) { i ->
            val isZ = type == ParticleType.Z
            particles.add(
                Particle(
                    type = type,
                    x = (random.nextFloat() - 0.5f) * 0.9f + (if (isZ) 0.55f else 0f),
                    y = -0.7f - random.nextFloat() * 0.2f,
                    vx = (random.nextFloat() - 0.5f) * 0.35f + (if (isZ) 0.18f else 0f),
                    vy = -(0.45f + random.nextFloat() * 0.35f),
                    age = -i * 0.14f,
                    life = 1.3f + random.nextFloat() * 0.5f,
                    rot = random.nextFloat() * TWO_PI,
                    size = 0.15f + random.nextFloat() * 0.08f
                )
            )
        }
    }

    /** Mailbox swallow — opens the slot, chews, then closes. */
    fun gulp() {
        slotHTarget = 0.42f
        schedule(0.46f) {
            slotHTarget = 0f
            chewing = true
            schedule(0.8f) { chewing = false }
        }
        anim(
            Prop.SY,
            listOf(
                TweenKey(0.78f, 80f, ::easeOut),
                TweenKey(1.18f, 130f, ::easeOut),
                TweenKey(1f, 220f, ::easeBack)
            )
        )
        anim(
            Prop.SX,
            listOf(
                TweenKey(1.28f, 80f, ::easeOut),
                TweenKey(0.92f, 130f, ::easeOut),
                TweenKey(1f, 220f, ::easeBack)
            )
        )
        blink()
    }

    /** True while anything is still moving — lets the host idle its frame loop. */
    val busy: Boolean
        get() = tweens.isNotEmpty() ||
            particles.isNotEmpty() ||
            cfg.bounces || cfg.scans || cfg.breathes || cfg.zz || cfg.sweat ||
            abs(tgYaw - yaw) > 0.002f ||
            abs(tgPitch - pitch) > 0.002f ||
            abs(tgTilt - tilt) > 0.002f ||
            abs(tgSy - sy) > 0.002f ||
            abs(tgSx - sx) > 0.002f ||
            slotH > 0.001f || abs(slotHVel) > 0.001f ||
            abs(col.r - colT.r) > 0.003f ||
            abs(col.g - colT.g) > 0.003f ||
            abs(col.b - colT.b) > 0.003f

    /** Advances every animated property by [dt] seconds. This is the only clock. */
    fun update(dt: Float) {
        if (dt <= 0f) return
        val step = dt.coerceAtMost(MAX_STEP)
        time += step

        runScheduled()

        for (tween in tweens.values.toList()) {
            val key = tween.keys[tween.index]
            tween.elapsedMs += step * 1000f
            val p = (tween.elapsedMs / key.durationMs).coerceIn(0f, 1f)
            set(tween.prop, tween.from + (key.target - tween.from) * key.ease(p))
            if (p >= 1f) {
                tween.from = key.target
                tween.index++
                tween.elapsedMs = 0f
                if (tween.index >= tween.keys.size) {
                    tweens.remove(tween.prop)
                    locks.remove(tween.prop)
                    tween.onComplete?.invoke()
                }
            }
        }

        val t = time
        // Upstream `miniLook*`: the wander owns the gaze only while nothing else wants it.
        if (lookOverride && t > lookOverrideUntil) releaseLook()
        tickLookAround(t)

        var ty = gazeX * 0.62f
        var tp = gazeY * 0.5f

        cfg.look?.let { look ->
            ty = ty * 0.35f + look.x * 0.55f
            tp = tp * 0.3f + look.y * 0.5f
        }
        if (cfg.scans) {
            ty = sin(t * 2.6f) * 0.6f
            tp = -0.06f
        }
        if (state == CoucouState.SLEEPING) {
            ty = 0f
            tp = -0.14f
        }
        if (state == CoucouState.DIZZY) ty = sin(t * 9f) * 0.25f

        tgYaw = ty
        tgPitch = tp
        tgTilt = cfg.tilt

        if (t > waveStart && t < waveUntil) {
            val wt = t - waveStart
            tgTilt = -0.06f + sin(TWO_PI * 1.2f * wt) * 0.07f
        }

        val bounce = if (cfg.bounces) -abs(sin(t * 5.2f)) * 0.07f else 0f
        val kGen = 1f - pow(0.0008f, step)
        if (!locks.contains(Prop.OY)) oy += (bounce - oy) * kGen

        if (cfg.breathes) {
            tgSy = 1f + sin(t * 1.8f) * BREATH_AMP
            tgSx = 1f - sin(t * 1.8f) * BREATH_AMP * 0.57f
        } else {
            tgSy = 1f
            tgSx = 1f
        }

        val kLook = 1f - pow(0.0025f, step)
        if (!locks.contains(Prop.YAW)) yaw += (tgYaw - yaw) * kLook
        if (!locks.contains(Prop.PITCH)) pitch += (tgPitch - pitch) * kLook
        if (!locks.contains(Prop.TILT)) tilt += (tgTilt - tilt) * kGen
        if (!locks.contains(Prop.SY)) sy += (tgSy - sy) * kGen
        if (!locks.contains(Prop.SX)) sx += (tgSx - sx) * kGen
        // `es` has no per-state target upstream either: it only moves through tweens
        // (the surprised emote) and springs back to 1 otherwise.
        if (!locks.contains(Prop.ES)) es += (1f - es) * kGen

        col = col.mix(colT, 1f - pow(0.002f, step))

        if (t > nextBlink) {
            if (state != CoucouState.SLEEPING && state != CoucouState.DIZZY) {
                blink()
                if (random.nextFloat() < 0.22f) schedule(0.23f) { blink() }
            }
            nextBlink = t + 2.2f + random.nextFloat() * 3.2f
        }

        if (eyeOverride != null && t > eyeOverrideUntil) {
            eyeOverride = permanentEye
            if (permanentEye != null) eyeOverrideUntil = Float.MAX_VALUE
        }

        if (t - lastAmbient > 1.3f) {
            lastAmbient = t
            if (cfg.zz) emit(ParticleType.Z, 1)
            if (cfg.sweat && random.nextFloat() < 0.5f) emit(ParticleType.SWEAT, 1)
        }

        for (p in particles) p.age += step
        particles.removeAll { it.age >= it.life }

        // Mouth slot spring — ω₀ = 2π/0.25, ζ = 0.6
        val acc = OMEGA * OMEGA * (slotHTarget - slotH) - 2f * ZETA * OMEGA * slotHVel
        slotHVel += acc * step
        slotH = maxOf(0f, slotH + slotHVel * step)
    }

    // endregion

    // region derived values the renderer reads

    val isChewing: Boolean get() = chewing

    val mouthOpen: Float get() = slotH

    val effectiveEye: EyeShape
        get() = if (morph > 0.5f) {
            when {
                chewing -> EyeShape.HAPPY
                slotHTarget > 0.05f || slotH > 0.1f -> EyeShape.CUP
                else -> EyeShape.PILL
            }
        } else {
            eyeOverride ?: cfg.eye
        }

    val effectiveTint: Float get() = tint * (1f - morph)

    val effectiveBlush: Float get() = maxOf(blush, tint * 0.5f) * (1f - morph)

    /** True while the greet wave is on screen, so the renderer can raise the hand. */
    val waving: Boolean get() = waveStart > 0f && time >= waveStart && time < waveUntil

    // endregion

    // region internals

    /**
     * Ambient look-around scheduler.
     *
     * Ports upstream `miniLookTarget` verbatim: a new random target every
     * `0.5 + rand(0..1.5)` s within `x ∈ [-0.88, 0.88]`, `y ∈ [-0.55, 0.45]`, and only
     * while no state has a gaze of its own and nothing is scanning. States that do
     * (`thinking`, `searching`, `sleeping`, `dizzy`) override the result further down in
     * [update], exactly as they do upstream.
     *
     * Note on timings: the report in `docs/coucou_desktop_prompt_box_and_animation_report.md`
     * §2.3 quotes a 2.0–5.0 s retarget, but the line it cites (`engine.ts:528`) is
     * `nextTime = n + 0.5 + Math.random() * 1.5`, i.e. 0.5–2.0 s. The code wins; the
     * constants below are the single place to change if Boss prefers the slower cadence.
     */
    private fun tickLookAround(t: Float) {
        if (lookOverride || !ambientLook) return
        if (cfg.look != null || cfg.scans) return
        if (state == CoucouState.SLEEPING || state == CoucouState.DIZZY) return
        if (t <= nextLookAt) return
        ambientLookX = LOOK_MIN_X + random.nextFloat() * (LOOK_MAX_X - LOOK_MIN_X)
        ambientLookY = LOOK_MIN_Y + random.nextFloat() * (LOOK_MAX_Y - LOOK_MIN_Y)
        nextLookAt = t + LOOK_FIRST_DELAY + random.nextFloat() * LOOK_DELAY_SPREAD
    }

    private fun runScheduled() {
        if (scheduled.isEmpty()) return
        val due = mutableListOf<Scheduled>()
        val pending = mutableListOf<Scheduled>()
        for (s in scheduled) {
            if (s.at <= time) due.add(s) else pending.add(s)
        }
        scheduled.clear()
        scheduled.addAll(pending)
        due.sortedBy { it.at }.forEach { it.action() }
    }

    private fun anim(prop: Prop, keys: List<TweenKey>, onComplete: (() -> Unit)? = null) {
        tweens[prop] = Tween(prop, keys, 0, get(prop), 0f, onComplete)
        locks.add(prop)
    }

    private fun schedule(delaySec: Float, action: () -> Unit) {
        scheduled.add(Scheduled(time + delaySec, action))
    }

    private fun get(prop: Prop): Float = when (prop) {
        Prop.YAW -> yaw
        Prop.PITCH -> pitch
        Prop.ROLL -> roll
        Prop.TILT -> tilt
        Prop.OPEN -> open
        Prop.SX -> sx
        Prop.SY -> sy
        Prop.OY -> oy
        Prop.OX -> ox
        Prop.TINT -> tint
        Prop.MORPH -> morph
        Prop.HANDS -> hands
        Prop.BLUSH -> blush
        Prop.ES -> es
        Prop.BADGE_S -> badgeS
    }

    private fun set(prop: Prop, value: Float) {
        when (prop) {
            Prop.YAW -> yaw = value
            Prop.PITCH -> pitch = value
            Prop.ROLL -> roll = value
            Prop.TILT -> tilt = value
            Prop.OPEN -> open = value
            Prop.SX -> sx = value
            Prop.SY -> sy = value
            Prop.OY -> oy = value
            Prop.OX -> ox = value
            Prop.TINT -> tint = value
            Prop.MORPH -> morph = value
            Prop.HANDS -> hands = value
            Prop.BLUSH -> blush = value
            Prop.ES -> es = value
            Prop.BADGE_S -> badgeS = value
        }
    }

    private class Tween(
        val prop: Prop,
        val keys: List<TweenKey>,
        var index: Int,
        var from: Float,
        var elapsedMs: Float,
        val onComplete: (() -> Unit)?
    )

    private class Scheduled(val at: Float, val action: () -> Unit)

    // endregion

    // region value types

    internal enum class Prop {
        YAW, PITCH, ROLL, TILT, OPEN, SX, SY, OY, OX, TINT, MORPH, HANDS, BLUSH, ES, BADGE_S
    }

    internal class TweenKey(val target: Float, val durationMs: Float, val ease: (Float) -> Float)

    enum class EyeShape {
        PILL, WIDE, DOT, LINE, FLAT, HAPPY, CLOSED, SPIRAL, HEART, STAR, TIRED, WINK, CUP
    }

    enum class BadgeKind { DOTS, BANG, QUESTION, DOT }

    enum class ParticleType { HEART, STAR, SPARK, SWEAT, Z }

    enum class Emote(val eye: EyeShape) {
        LOVE(EyeShape.HEART),
        SURPRISED(EyeShape.DOT),
        PROUD(EyeShape.STAR),
        WINK(EyeShape.WINK),
        YAWN(EyeShape.TIRED),
        HAPPY(EyeShape.HAPPY),
        ANNOYED(EyeShape.LINE)
    }

    class Particle(
        val type: ParticleType,
        val x: Float,
        val y: Float,
        val vx: Float,
        val vy: Float,
        var age: Float,
        val life: Float,
        val rot: Float,
        val size: Float
    )

    data class Badge(val kind: BadgeKind, val color: Rgb)

    data class StateCfg(
        val color: Rgb,
        val tint: Float,
        val eye: EyeShape,
        val badge: Badge?,
        val bounces: Boolean = false,
        val scans: Boolean = false,
        val breathes: Boolean = false,
        val zz: Boolean = false,
        val sweat: Boolean = false,
        val look: Look? = null,
        val tilt: Float = 0f
    )

    data class Look(val x: Float, val y: Float)

    /** Colour triple with components in 0..1 — the representation upstream `engine.ts` uses. */
    data class Rgb(val r: Float, val g: Float, val b: Float) {
        fun mix(other: Rgb, t: Float): Rgb =
            Rgb(r + (other.r - r) * t, g + (other.g - g) * t, b + (other.b - b) * t)
    }

    /** Things the character asks its host to do. */
    sealed interface CharacterEvent {
        data class Sound(val sound: SoundPlayer.Sound) : CharacterEvent
        data object Dizzy : CharacterEvent
    }

    // endregion

    companion object {
        const val EYE_W = 0.25f
        const val EYE_H = 0.27f
        const val EYE_SP = 0.37f
        const val EYE_P = -0.12f

        /** Superellipse exponent: 1 would be an ellipse, higher is a squarer capsule. */
        const val BODY_EXPONENT = 2.0f / 2.7f

        private const val OUTLINE_POINTS = 72
        private const val MAX_STEP = 0.1f
        private const val BREATH_AMP = 0.035f
        private const val TWO_PI = 6.2831855f
        private const val OMEGA = 25.132742f // 2π / 0.25
        private const val ZETA = 0.6f

        /** Caret follow holds the gaze this long after the last keystroke. */
        const val CARET_LOOK_HOLD = 1.5f

        /** Vertical caret-follow gaze: looking slightly down at the input (report §2.4). */
        const val CARET_LOOK_Y = 0.2f

        /**
         * Maps a caret index to a normalised gaze `x`, as upstream does while typing.
         *
         * `(caret / length) * 2 - 1`, clamped to -1..1. An empty field maps to 0 (centred),
         * and a negative caret — the value `selectionStart` reports when the field loses
         * focus mid-edit — is treated as "unknown" and centred too, rather than snapping
         * the gaze to the far left.
         */
        fun caretLookX(caret: Int, length: Int): Float {
            if (length <= 0 || caret < 0) return 0f
            val x = (caret.coerceAtMost(length).toFloat() / length) * 2f - 1f
            return x.coerceIn(-1f, 1f)
        }

        /** Ambient look-around: upstream `miniLookTarget` ranges and cadence. */
        const val LOOK_MIN_X = -0.88f
        const val LOOK_MAX_X = 0.88f
        const val LOOK_MIN_Y = -0.55f
        const val LOOK_MAX_Y = 0.45f
        const val LOOK_FIRST_DELAY = 0.5f
        const val LOOK_DELAY_SPREAD = 1.5f

        private fun pow(v: Float, p: Float): Float =
            Math.pow(v.toDouble(), p.toDouble()).toFloat()

        fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t) * (1f - t)

        fun easeInOut(t: Float): Float =
            if (t < 0.5f) 4f * t * t * t else 1f - pow(-2f * t + 2f, 3f) / 2f

        fun easeBack(t: Float): Float {
            val c1 = 1.7f
            val c3 = c1 + 1f
            val d = t - 1f
            return 1f + c3 * d * d * d + c1 * d * d
        }

        fun easeLin(t: Float): Float = t

        fun easeIn(t: Float): Float = t * t * t

        /** Upstream state colours, in the same 0..1 form `engine.ts` uses. */
        val STATE_COLORS: Map<CoucouState, Rgb> = mapOf(
            CoucouState.IDLE to Rgb(0.902f, 0.914f, 0.933f),
            CoucouState.WORKING to Rgb(0.231f, 0.620f, 1.000f),
            CoucouState.THINKING to Rgb(0.545f, 0.361f, 0.965f),
            CoucouState.SEARCHING to Rgb(0.388f, 0.396f, 0.949f),
            CoucouState.APPROVAL to Rgb(0.961f, 0.647f, 0.141f),
            CoucouState.QUESTION to Rgb(0.133f, 0.827f, 0.933f),
            CoucouState.ERROR to Rgb(0.957f, 0.314f, 0.369f),
            CoucouState.FINISHED to Rgb(0.204f, 0.831f, 0.600f),
            CoucouState.RATELIMIT to Rgb(0.984f, 0.573f, 0.235f),
            CoucouState.SLEEPING to Rgb(0.580f, 0.635f, 0.722f),
            CoucouState.DIZZY to Rgb(0.957f, 0.447f, 0.714f)
        )

        val BOT_STATES: Map<CoucouState, StateCfg> = mapOf(
            CoucouState.IDLE to
                StateCfg(STATE_COLORS.getValue(CoucouState.IDLE), 0f, EyeShape.PILL, null),
            CoucouState.WORKING to StateCfg(
                STATE_COLORS.getValue(CoucouState.WORKING), 0.72f, EyeShape.PILL,
                Badge(BadgeKind.DOTS, STATE_COLORS.getValue(CoucouState.WORKING))
            ),
            CoucouState.THINKING to StateCfg(
                STATE_COLORS.getValue(CoucouState.THINKING), 0.72f, EyeShape.PILL,
                Badge(BadgeKind.DOTS, STATE_COLORS.getValue(CoucouState.THINKING)),
                look = Look(0.55f, 0.55f)
            ),
            CoucouState.SEARCHING to StateCfg(
                STATE_COLORS.getValue(CoucouState.SEARCHING), 0.72f, EyeShape.PILL,
                Badge(BadgeKind.DOTS, STATE_COLORS.getValue(CoucouState.SEARCHING)), scans = true
            ),
            CoucouState.APPROVAL to StateCfg(
                STATE_COLORS.getValue(CoucouState.APPROVAL), 0.78f, EyeShape.WIDE,
                Badge(BadgeKind.BANG, STATE_COLORS.getValue(CoucouState.APPROVAL)), bounces = true
            ),
            CoucouState.QUESTION to StateCfg(
                STATE_COLORS.getValue(CoucouState.QUESTION), 0.75f, EyeShape.PILL,
                Badge(BadgeKind.QUESTION, STATE_COLORS.getValue(CoucouState.QUESTION)),
                tilt = 0.17f
            ),
            CoucouState.ERROR to StateCfg(
                STATE_COLORS.getValue(CoucouState.ERROR), 0.78f, EyeShape.FLAT,
                Badge(BadgeKind.DOT, STATE_COLORS.getValue(CoucouState.ERROR))
            ),
            CoucouState.FINISHED to StateCfg(
                STATE_COLORS.getValue(CoucouState.FINISHED), 0.35f, EyeShape.HAPPY,
                Badge(BadgeKind.DOT, STATE_COLORS.getValue(CoucouState.FINISHED))
            ),
            CoucouState.RATELIMIT to StateCfg(
                STATE_COLORS.getValue(CoucouState.RATELIMIT), 0.72f, EyeShape.TIRED,
                Badge(BadgeKind.DOT, STATE_COLORS.getValue(CoucouState.RATELIMIT)), sweat = true
            ),
            CoucouState.SLEEPING to StateCfg(
                STATE_COLORS.getValue(CoucouState.SLEEPING), 0.32f, EyeShape.CLOSED, null,
                breathes = true, zz = true
            ),
            CoucouState.DIZZY to StateCfg(
                STATE_COLORS.getValue(CoucouState.DIZZY), 0.70f, EyeShape.SPIRAL, null
            )
        )

        /** State → sound, as in `BotStateCfg.sound` upstream. */
        val STATE_SOUND: Map<CoucouState, SoundPlayer.Sound> = mapOf(
            CoucouState.WORKING to SoundPlayer.Sound.WORK,
            CoucouState.THINKING to SoundPlayer.Sound.THINK,
            CoucouState.SEARCHING to SoundPlayer.Sound.SEARCH,
            CoucouState.APPROVAL to SoundPlayer.Sound.APPROVAL,
            CoucouState.QUESTION to SoundPlayer.Sound.QUESTION,
            CoucouState.ERROR to SoundPlayer.Sound.ERROR,
            CoucouState.FINISHED to SoundPlayer.Sound.FINISH,
            CoucouState.RATELIMIT to SoundPlayer.Sound.RATE,
            CoucouState.SLEEPING to SoundPlayer.Sound.SLEEP,
            CoucouState.DIZZY to SoundPlayer.Sound.DIZZY
        )

        private fun superSign(v: Float, exponent: Float): Float =
            if (v >= 0f) Math.pow(v.toDouble(), exponent.toDouble()).toFloat()
            else -Math.pow((-v).toDouble(), exponent.toDouble()).toFloat()

        /** Ray → rounded-rect boundary intersection, for the mailbox morph. */
        private fun rrPoint(ca: Float, sa: Float, w: Float, h: Float, cr: Float): FloatArray {
            val eps = 1e-6f
            val kx = if (ca >= 0f) 1f else -1f
            val ky = if (sa >= 0f) 1f else -1f
            val cx = kx * (w - cr)
            val cy = ky * (h - cr)

            val dot = ca * cx + sa * cy
            val disc = dot * dot - (cx * cx + cy * cy - cr * cr)
            if (disc >= 0f) {
                val t = dot + sqrt(disc)
                if (t > eps) {
                    val px = ca * t
                    val py = sa * t
                    if (abs(px) >= w - cr - eps && abs(py) >= h - cr - eps) {
                        return floatArrayOf(px, py)
                    }
                }
            }
            if (abs(sa) > eps) {
                val t = (ky * h) / sa
                if (t > eps) {
                    val px = ca * t
                    if (abs(px) <= w - cr + eps) return floatArrayOf(px, ky * h)
                }
            }
            if (abs(ca) > eps) {
                val t = (kx * w) / ca
                if (t > eps) {
                    val py = sa * t
                    if (abs(py) <= h - cr + eps) return floatArrayOf(kx * w, py)
                }
            }
            return floatArrayOf(kx * w, ky * h)
        }
    }
}

/**
 * Colour set for the character.
 *
 * The defaults match upstream `engine.ts` so the character renders correctly before
 * @Buffy's tokens land; [CoucouCharacterView] overrides them from theme resources when
 * those exist. Nothing here hard-codes a colour at the call site, which keeps the
 * zero-literal-colours contract that @Buffy enforces across the project.
 */
data class CoucouPalette(
    val baseTop: CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(0.929f, 0.929f, 0.937f),
    val baseBottom: CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(0.769f, 0.773f, 0.792f),
    val ink: CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(0.102f, 0.078f, 0.071f),
    val heart: CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(1f, 0.302f, 0.427f),
    val star: CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(0.969f, 0.702f, 0.169f),
    val spark: CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(1f, 1f, 1f),
    val sweat: CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(0.486f, 0.780f, 1f),
    val sleep: CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(0.820f, 0.859f, 0.922f),
    val stateColors: Map<CoucouState, CoucouCharacterEngine.Rgb> =
        CoucouCharacterEngine.STATE_COLORS
) {
    fun stateColor(state: CoucouState): CoucouCharacterEngine.Rgb =
        stateColors[state] ?: CoucouCharacterEngine.STATE_COLORS.getValue(state)
}