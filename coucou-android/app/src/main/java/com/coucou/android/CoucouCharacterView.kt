package com.coucou.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Android Canvas 2D renderer for the procedural character.
 *
 * Rendering is a port of `draw()` in `coucou/windows/src/mochi/engine.ts`; every number
 * that drives it lives in [CoucouCharacterEngine] (pure Kotlin, JVM-testable), so this
 * class only turns engine state into `Canvas` calls:
 *
 *   - body: superellipse capsule, vertical gradient, state tint, edge shading, highlight
 *   - eyes: pill/wide/dot/line/flat/happy/closed/spiral/heart/star/tired/wink/cup
 *   - badge: dots / bang / question / dot, top-left of the body
 *   - particles: hearts, stars, sparks, sweat, sleep "z"
 *   - hands: two capsules behind the body, one of which waves during [greet]
 *
 * Integration notes:
 *  - Colours come from theme tokens when @Buffy's exist and fall back to the upstream
 *    defaults otherwise. They are resolved *by name*, for the same reason [SoundPlayer]
 *    resolves audio by name: a compiled `R.color.*` reference would break every peer's
 *    build until the tokens land.
 *  - The frame loop is a [ValueAnimator] that starts on attach and stops on detach, so a
 *    hidden or destroyed bubble costs nothing (upstream's "0 % CPU when hidden" rule).
 */
class CoucouCharacterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val engine = CoucouCharacterEngine(palette = resolvePalette(context))

    /** Host callback for sounds/behaviour the character triggers itself. */
    var onEvent: ((CoucouCharacterEngine.CharacterEvent) -> Unit)? = null

    /** True while the frame loop is running; QA can assert the bubble is really animating. */
    var isAnimating: Boolean = false
        private set

    private var animator: ValueAnimator? = null
    private var lastFrameNanos = 0L

    private val bodyPath = Path()
    private val shapePath = Path()
    private val bodyPoints = FloatArray((BODY_SEGMENTS + 1) * 2)
    private val tmpRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    init {
        engine.onEvent = { event -> onEvent?.invoke(event) }
    }

    // region public API

    /** The state currently on screen. */
    val characterState: CoucouState get() = engine.state

    /** Sets the character state; returns false when it was already showing [next]. */
    fun setState(next: CoucouState, force: Boolean = false): Boolean = engine.setState(next, force)

    /** The "coucou" peek wave — plays when the bubble first appears. */
    fun greet() {
        engine.greet()
    }

    /** Tap feedback: squash + blink. */
    fun onTap() {
        engine.squash()
        engine.blink()
    }

    /** Three fast taps make the character dizzy (engine-side rule, upstream `slap()`). */
    fun onRapidTap() {
        engine.slap()
    }

    fun triggerEmote(emote: CoucouCharacterEngine.Emote, duration: Float = 1.8f) {
        engine.triggerEmote(emote, duration)
    }

    fun emit(type: CoucouCharacterEngine.ParticleType, count: Int) {
        engine.emit(type, count)
    }

    /** Points the eyes at a normalised (-1..1) position. */
    fun setLook(x: Float, y: Float) {
        engine.lookX = x.coerceIn(-1f, 1f)
        engine.lookY = y.coerceIn(-1f, 1f)
    }

    fun resetLook() = setLook(0f, 0f)

    /** Set by the host when the character should animate even while not visible. */
    var animate: Boolean = true
        set(value) {
            field = value
            if (value) startLoop() else stopLoop()
        }

    // endregion

    // region frame loop

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (!animate || animator != null || windowVisibility != VISIBLE) return
        lastFrameNanos = 0L
        val running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FRAME_INTERVAL_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = System.nanoTime()
                val dt = if (lastFrameNanos == 0L) {
                    FRAME_INTERVAL_MS / 1000f
                } else {
                    (now - lastFrameNanos) / 1_000_000_000f
                }
                lastFrameNanos = now
                engine.update(dt)
                invalidate()
            }
        }
        animator = running
        isAnimating = true
        running.start()
    }

    private fun stopLoop() {
        animator?.cancel()
        animator = null
        isAnimating = false
    }

    // endregion

    // region drawing

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val r = w * RADIUS_RATIO
        val rx = r * 1.14f
        val ry = r * 0.88f
        val cx = w / 2f + engine.ox * r
        val cy = h / 2f + engine.oy * r + r * 0.06f

        drawHandsBehind(canvas, r, rx, ry, cx, cy)

        val save = canvas.save()
        canvas.translate(cx, cy)
        if (engine.tilt != 0f) canvas.rotate(toDegrees(engine.tilt))
        canvas.scale(engine.sx, engine.sy)

        buildBodyPath(rx, ry, r)
        drawBody(canvas, rx, ry, r)
        drawBlush(canvas, r, rx, ry)
        drawEyes(canvas, r, rx, ry)
        drawMouth(canvas, r)

        canvas.restoreToCount(save)

        val badge = engine.badge
        if (badge != null && engine.badgeS > 0.01f && engine.morph < 0.25f) {
            drawBadge(canvas, badge, r, cx, cy)
        }
        drawParticles(canvas, r, cx, cy)
    }

    private fun buildBodyPath(rx: Float, ry: Float, r: Float) {
        val n = engine.bodyOutline(rx, ry, r, bodyPoints)
        bodyPath.rewind()
        bodyPath.moveTo(bodyPoints[0], bodyPoints[1])
        for (i in 1..n) {
            bodyPath.lineTo(bodyPoints[i * 2], bodyPoints[i * 2 + 1])
        }
        bodyPath.close()
    }

    private fun drawBody(canvas: Canvas, rx: Float, ry: Float, r: Float) {
        val palette = engine.palette
        fillPaint.shader = LinearGradient(
            rx * 0.7f, -ry * 0.85f, -rx * 0.8f, ry * 0.9f,
            intArrayOf(opaque(palette.baseTop), opaque(palette.baseBottom)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bodyPath, fillPaint)

        val effectiveTint = engine.effectiveTint
        if (effectiveTint > 0.01f) {
            fillPaint.shader = LinearGradient(
                0f, ry, 0f, -ry,
                intArrayOf(
                    withAlpha(engine.col, 0.72f * effectiveTint),
                    withAlpha(engine.col, 0f)
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(bodyPath, fillPaint)
        }

        fillPaint.shader = RadialGradient(
            0f, 0f, r * 1.25f,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, scaled(Color.BLACK, 0.2f)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bodyPath, fillPaint)

        fillPaint.shader = RadialGradient(
            rx * 0.34f, -ry * 0.46f, r * 0.42f,
            intArrayOf(scaled(Color.WHITE, 0.55f), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bodyPath, fillPaint)
        fillPaint.shader = null
    }

    private fun drawBlush(canvas: Canvas, r: Float, rx: Float, ry: Float) {
        val value = engine.effectiveBlush
        if (value <= 0.01f) return
        val save = canvas.save()
        canvas.clipPath(bodyPath)
        val yOffset = sin(engine.yaw) * rx * 0.8f
        fillPaint.shader = null
        fillPaint.color = Color.argb(
            (0.5f * value).coerceIn(0f, 1f).times(255f).toInt(), 255, 120, 150
        )
        for (sd in intArrayOf(-1, 1)) {
            val centerX = sd * rx * 0.55f + yOffset
            tmpRect.set(
                centerX - r * 0.17f,
                ry * 0.2f - r * 0.1f,
                centerX + r * 0.17f,
                ry * 0.2f + r * 0.1f
            )
            canvas.drawOval(tmpRect, fillPaint)
        }
        canvas.restoreToCount(save)
    }

    private fun drawEyes(canvas: Canvas, r: Float, rx: Float, ry: Float) {
        val shape = engine.effectiveEye
        val save = canvas.save()
        canvas.clipPath(bodyPath)
        val ink = opaque(engine.palette.ink)
        fillPaint.shader = null
        fillPaint.color = ink
        strokePaint.color = ink

        for (sd in intArrayOf(-1, 1)) {
            val eyeYaw = sd * CoucouCharacterEngine.EYE_SP + engine.yaw
            val eyePitch = wrapAngle(CoucouCharacterEngine.EYE_P + engine.pitch + engine.roll)
            val cp = cos(eyePitch)
            if (cos(eyeYaw) * cp <= 0.04f) continue

            val ex = sin(eyeYaw) * cp * rx
            val ey = -sin(eyePitch) * ry
            val fx = lerp(max(0.18f, cos(eyeYaw)), 1f, engine.morph * 0.7f)
            val fy = lerp(max(0.18f, cp), 1f, engine.morph * 0.7f)
            val ew = r * CoucouCharacterEngine.EYE_W * engine.es
            val eh = r * CoucouCharacterEngine.EYE_H * engine.es

            val eyeSave = canvas.save()
            canvas.translate(ex, ey)
            canvas.scale(fx, fy)
            drawEyeShape(canvas, shape, ew, eh, sd)
            canvas.restoreToCount(eyeSave)
        }
        canvas.restoreToCount(save)
    }

    private fun drawEyeShape(
        canvas: Canvas,
        shape: CoucouCharacterEngine.EyeShape,
        w: Float,
        h: Float,
        sd: Int
    ) {
        val open = engine.open
        val time = engine.time
        when (shape) {
            CoucouCharacterEngine.EyeShape.WIDE ->
                drawEyeShape(canvas, CoucouCharacterEngine.EyeShape.PILL, w * 1.16f, h * 1.12f, sd)

            CoucouCharacterEngine.EyeShape.PILL -> {
                val hh = max(h * open, w * 0.3f)
                roundRect(-w / 2f, -hh / 2f, w, hh, min(w / 2f, hh / 2f))
                canvas.drawPath(shapePath, fillPaint)
            }

            CoucouCharacterEngine.EyeShape.DOT ->
                canvas.drawCircle(0f, 0f, w * 0.45f, fillPaint)

            CoucouCharacterEngine.EyeShape.LINE -> {
                canvas.rotate(-sd * 0.2f * DEGREES)
                roundRect(-w * 0.78f, -w * 0.21f, w * 1.56f, w * 0.42f, w * 0.21f)
                canvas.drawPath(shapePath, fillPaint)
            }

            CoucouCharacterEngine.EyeShape.FLAT -> {
                roundRect(-w * 0.72f, -w * 0.2f, w * 1.44f, w * 0.4f, w * 0.2f)
                canvas.drawPath(shapePath, fillPaint)
            }

            CoucouCharacterEngine.EyeShape.HAPPY -> {
                strokePaint.strokeWidth = w * 0.5f
                canvas.drawArc(arcBounds(0f, h * 0.18f, w * 0.82f), 201.6f, 136.8f, false, strokePaint)
            }

            CoucouCharacterEngine.EyeShape.CLOSED -> {
                strokePaint.strokeWidth = w * 0.36f
                canvas.drawArc(arcBounds(0f, -h * 0.08f, w * 0.78f), 27f, 126f, false, strokePaint)
            }

            CoucouCharacterEngine.EyeShape.SPIRAL -> {
                strokePaint.strokeWidth = w * 0.22f
                shapePath.rewind()
                var a = 0f
                val limit = 4.4f * Math.PI.toFloat()
                while (a < limit) {
                    val radius = w * 0.06f + a * w * 0.058f
                    val angle = a + time * 9f * sd
                    val px = cos(angle) * radius
                    val py = sin(angle) * radius
                    if (a == 0f) shapePath.moveTo(px, py) else shapePath.lineTo(px, py)
                    a += 0.2f
                }
                canvas.drawPath(shapePath, strokePaint)
            }

            CoucouCharacterEngine.EyeShape.HEART -> {
                fillPaint.color = opaque(engine.palette.heart)
                heart(w * 1.2f)
                canvas.drawPath(shapePath, fillPaint)
                fillPaint.color = opaque(engine.palette.ink)
            }

            CoucouCharacterEngine.EyeShape.STAR -> {
                fillPaint.color = opaque(engine.palette.star)
                canvas.rotate(toDegrees(time * 1.5f * sd))
                star(w * 1.05f, w * 0.46f)
                canvas.drawPath(shapePath, fillPaint)
                fillPaint.color = opaque(engine.palette.ink)
            }

            CoucouCharacterEngine.EyeShape.TIRED -> {
                roundRect(-w / 2f, -h * 0.02f, w, h * 0.38f, w / 2f)
                canvas.drawPath(shapePath, fillPaint)
                roundRect(-w * 0.62f, -h * 0.1f, w * 1.24f, w * 0.22f, w * 0.11f)
                canvas.drawPath(shapePath, fillPaint)
            }

            CoucouCharacterEngine.EyeShape.WINK -> {
                if (sd < 0) {
                    val hh = max(h * open, w * 0.3f)
                    roundRect(-w / 2f, -hh / 2f, w, hh, min(w / 2f, hh / 2f))
                    canvas.drawPath(shapePath, fillPaint)
                } else {
                    strokePaint.strokeWidth = w * 0.5f
                    canvas.drawArc(
                        arcBounds(0f, h * 0.18f, w * 0.82f), 201.6f, 136.8f, false, strokePaint
                    )
                }
            }

            // Flat top, rounded bottom corners (U shape) — used while the slot is open.
            CoucouCharacterEngine.EyeShape.CUP -> {
                val hh = max(h * open, w * 0.3f)
                val cr = min(w / 2f, hh / 2f)
                shapePath.rewind()
                shapePath.moveTo(-w / 2f, -hh / 2f)
                shapePath.lineTo(w / 2f, -hh / 2f)
                shapePath.lineTo(w / 2f, hh / 2f - cr)
                shapePath.quadTo(w / 2f, hh / 2f, w / 2f - cr, hh / 2f)
                shapePath.lineTo(-w / 2f + cr, hh / 2f)
                shapePath.quadTo(-w / 2f, hh / 2f, -w / 2f, hh / 2f - cr)
                shapePath.close()
                canvas.drawPath(shapePath, fillPaint)
            }
        }
    }

    /** Mailbox slot: a dark pill cut into the face, with a rim highlight. */
    private fun drawMouth(canvas: Canvas, r: Float) {
        val m = engine.morph
        if (m <= 0.05f) return
        val hW = r * 1.8f * m
        val hH = engine.mouthOpen * r * m
        val hX = -hW / 2f
        val boxTop = -r * (0.88f + 0.06f * m)
        val hY = boxTop + r * 0.08f * m

        val save = canvas.save()
        canvas.clipPath(bodyPath)

        strokePaint.color = scaled(Color.WHITE, 0.55f * m)
        strokePaint.strokeWidth = 1f
        canvas.drawLine(-r * 0.9f * m, boxTop + 1f, r * 0.9f * m, boxTop + 1f, strokePaint)

        if (hH > 0.8f) {
            val hR = min(hW / 2f, hH / 2f)
            fillPaint.shader = LinearGradient(
                0f, hY, 0f, hY + hH,
                intArrayOf(Color.rgb(7, 8, 10), Color.rgb(16, 19, 26)),
                null, Shader.TileMode.CLAMP
            )
            roundRect(hX, hY, hW, hH, hR)
            canvas.drawPath(shapePath, fillPaint)
            fillPaint.shader = null
            if (hH > 4f) {
                val lipR = min(hR, (hW - 2f) / 2f)
                strokePaint.color = scaled(Color.WHITE, 0.28f * m)
                canvas.drawLine(hX + lipR, hY + hH - 0.5f, hX + hW - lipR, hY + hH - 0.5f, strokePaint)
            }
        }
        canvas.restoreToCount(save)
    }

    /** Hands sit behind the body, in world coordinates (drawn before it, like upstream). */
    private fun drawHandsBehind(canvas: Canvas, r: Float, rx: Float, ry: Float, cx: Float, cy: Float) {
        if (engine.hands <= 0.01f || r <= MIN_HAND_RADIUS) return
        val bodyH = 2f * ry
        val hew = 0.3f * ry * engine.hands
        val heh = 0.26f * ry * engine.hands
        val hwB = rx * engine.sx
        val hhB = ry * engine.sy
        val waving = engine.waving
        val time = engine.time

        for (sd in intArrayOf(-1, 1)) {
            var localX: Float
            var localY: Float
            var handRot = 0f

            if (sd > 0 && waving) {
                val wt = time - engine.waveStart
                val rise = min(1f, wt / 0.18f)
                val riseEased = 1f - (1f - rise) * (1f - rise) * (1f - rise)
                val restX = hwB * 1.08f
                val restY = hhB * 0.7f
                val waveX = hwB * 1.1f + cos(13f * wt) * 0.06f * bodyH
                val waveY = -hhB * 0.15f - sin(13f * wt) * 0.14f * bodyH
                localX = restX + (waveX - restX) * riseEased
                localY = restY + (waveY - restY) * riseEased
                handRot = (-0.5f + sin(13f * wt) * 0.35f) * riseEased
            } else if (sd < 0 && waving) {
                val wt = time - engine.waveStart
                localX = -hwB * 1.08f
                localY = hhB * 0.7f + sin(6f * wt) * 0.04f * bodyH
            } else {
                localX = sd * hwB * 1.08f
                localY = hhB * 0.7f
            }

            val cosT = cos(engine.tilt)
            val sinT = sin(engine.tilt)
            val worldX = cx + cosT * localX - sinT * localY
            val worldY = cy + sinT * localX + cosT * localY

            val save = canvas.save()
            canvas.translate(worldX, worldY)
            if (handRot != 0f) canvas.rotate(toDegrees(handRot))
            fillPaint.shader = LinearGradient(
                hew * 0.7f, -heh * 0.85f, -hew * 0.8f, heh * 0.9f,
                intArrayOf(opaque(engine.palette.baseTop), opaque(engine.palette.baseBottom)),
                null, Shader.TileMode.CLAMP
            )
            tmpRect.set(-hew, -heh, hew, heh)
            canvas.drawOval(tmpRect, fillPaint)
            fillPaint.shader = null
            strokePaint.color = scaled(Color.BLACK, 0.08f)
            strokePaint.strokeWidth = 1f
            canvas.drawOval(tmpRect, strokePaint)
            canvas.restoreToCount(save)
        }
    }

    private fun drawBadge(
        canvas: Canvas,
        badge: CoucouCharacterEngine.Badge,
        r: Float,
        cx: Float,
        cy: Float
    ) {
        val bs = engine.badgeS
        val bx = cx - r * 0.72f * engine.sx
        val by = cy - r * 0.72f * engine.sy
        val color = opaque(badge.color)

        val save = canvas.save()
        canvas.translate(bx, by)
        canvas.scale(bs, bs)
        fillPaint.shader = null

        when (badge.kind) {
            CoucouCharacterEngine.BadgeKind.DOTS -> {
                val pw = r * 0.72f
                val ph = r * 0.36f
                fillPaint.color = color
                roundRect(-pw / 2f, -ph / 2f, pw, ph, ph / 2f)
                canvas.drawPath(shapePath, fillPaint)
                val t = engine.time
                for (i in 0..2) {
                    val phase = ((t * 2.4f - i * 0.22f) % 1f + 1f) % 1f
                    val dotR = r * 0.055f * (1f + 0.4f * max(0f, sin(phase * TWO_PI)))
                    fillPaint.color = Color.WHITE
                    canvas.drawCircle((i - 1) * r * 0.18f, 0f, dotR, fillPaint)
                }
            }

            CoucouCharacterEngine.BadgeKind.BANG,
            CoucouCharacterEngine.BadgeKind.QUESTION -> {
                fillPaint.color = Color.BLACK
                canvas.drawCircle(0f, 0f, r * 0.3f, fillPaint)
                fillPaint.color = color
                canvas.drawCircle(0f, 0f, r * 0.23f, fillPaint)
                textPaint.color = Color.WHITE
                textPaint.textSize = r * 0.32f
                val label =
                    if (badge.kind == CoucouCharacterEngine.BadgeKind.BANG) "!" else "?"
                canvas.drawText(label, 0f, textCenterOffset(), textPaint)
            }

            CoucouCharacterEngine.BadgeKind.DOT -> {
                fillPaint.color = Color.BLACK
                canvas.drawCircle(0f, 0f, r * 0.2f, fillPaint)
                fillPaint.color = color
                canvas.drawCircle(0f, 0f, r * 0.135f, fillPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    private fun drawParticles(canvas: Canvas, r: Float, cx: Float, cy: Float) {
        for (p in engine.particles) {
            if (p.age <= 0f) continue
            val k = p.age / p.life
            val rawAlpha = if (k < 0.2f) k / 0.2f else 1f - (k - 0.2f) / 0.8f
            val alpha = rawAlpha.coerceIn(0f, 1f)
            val px = cx + (p.x + p.vx * p.age) * r * 1.3f
            val py = cy + (p.y + p.vy * p.age) * r * 1.3f
            val sz = r * p.size * (1f + k * 0.4f)

            val save = canvas.save()
            canvas.translate(px, py)
            fillPaint.shader = null
            when (p.type) {
                CoucouCharacterEngine.ParticleType.HEART -> {
                    canvas.rotate(toDegrees(sin(p.age * 6f) * 0.3f))
                    fillPaint.color = scaledColor(engine.palette.heart, alpha)
                    heart(sz)
                    canvas.drawPath(shapePath, fillPaint)
                }
                CoucouCharacterEngine.ParticleType.STAR -> {
                    canvas.rotate(toDegrees(p.rot + p.age * 2f))
                    fillPaint.color = scaledColor(engine.palette.star, alpha)
                    star(sz, sz * 0.45f)
                    canvas.drawPath(shapePath, fillPaint)
                }
                CoucouCharacterEngine.ParticleType.SPARK -> {
                    canvas.rotate(toDegrees(p.rot))
                    fillPaint.color = scaledColor(engine.palette.spark, alpha)
                    star(sz * 0.8f, sz * 0.18f)
                    canvas.drawPath(shapePath, fillPaint)
                }
                CoucouCharacterEngine.ParticleType.SWEAT -> {
                    fillPaint.color = scaledColor(engine.palette.sweat, alpha)
                    shapePath.rewind()
                    shapePath.moveTo(0f, -sz)
                    shapePath.quadTo(sz * 0.8f, sz * 0.2f, 0f, sz * 0.6f)
                    shapePath.quadTo(-sz * 0.8f, sz * 0.2f, 0f, -sz)
                    shapePath.close()
                    canvas.drawPath(shapePath, fillPaint)
                }
                CoucouCharacterEngine.ParticleType.Z -> {
                    textPaint.color = scaledColor(engine.palette.sleep, alpha)
                    textPaint.textSize = sz * 1.9f
                    canvas.drawText("z", 0f, textCenterOffset(), textPaint)
                }
            }
            canvas.restoreToCount(save)
        }
    }

    // endregion

    // region path + colour helpers

    private fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        val r = max(0f, min(radius, min(w / 2f, h / 2f)))
        shapePath.rewind()
        shapePath.moveTo(x + r, y)
        shapePath.lineTo(x + w - r, y)
        shapePath.quadTo(x + w, y, x + w, y + r)
        shapePath.lineTo(x + w, y + h - r)
        shapePath.quadTo(x + w, y + h, x + w - r, y + h)
        shapePath.lineTo(x + r, y + h)
        shapePath.quadTo(x, y + h, x, y + h - r)
        shapePath.lineTo(x, y + r)
        shapePath.quadTo(x, y, x + r, y)
        shapePath.close()
    }

    private fun heart(s: Float) {
        shapePath.rewind()
        shapePath.moveTo(0f, s * 0.38f)
        shapePath.cubicTo(-s * 1.05f, -s * 0.15f, -s * 0.5f, -s * 0.95f, 0f, -s * 0.38f)
        shapePath.cubicTo(s * 0.5f, -s * 0.95f, s * 1.05f, -s * 0.15f, 0f, s * 0.38f)
        shapePath.close()
    }

    private fun star(ro: Float, ri: Float) {
        shapePath.rewind()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 1) ri else ro
            val a = -Math.PI.toFloat() / 2f + i * Math.PI.toFloat() / 5f
            val x = cos(a) * radius
            val y = sin(a) * radius
            if (i == 0) shapePath.moveTo(x, y) else shapePath.lineTo(x, y)
        }
        shapePath.close()
    }

    private fun arcBounds(cx: Float, cy: Float, r: Float): RectF = tmpRect.apply {
        set(cx - r, cy - r, cx + r, cy + r)
    }

    /** Draws text vertically centred on the current origin, like `textBaseline = middle`. */
    private fun textCenterOffset(): Float {
        val metrics = textPaint.fontMetrics
        return -(metrics.ascent + metrics.descent) / 2f
    }

    // endregion

    companion object {
        private const val RADIUS_RATIO = 0.3f
        private const val BODY_SEGMENTS = 72
        private const val FRAME_INTERVAL_MS = 16L
        private const val MIN_HAND_RADIUS = 14f
        private const val TWO_PI = 6.2831855f
        private const val DEGREES = 57.29578f

        /**
         * Token names used when @Buffy's `res/values` character tokens exist. Resolved by
         * name, never compiled, so the build stays green before they land.
         */
        private val TOKEN_NAMES = mapOf(
            "baseTop" to listOf("coucou_character_base_top", "character_base_top"),
            "baseBottom" to listOf("coucou_character_base_bottom", "character_base_bottom"),
            "ink" to listOf("coucou_character_ink", "character_ink"),
            "heart" to listOf("coucou_character_heart", "character_heart"),
            "star" to listOf("coucou_character_star", "character_star"),
            "sweat" to listOf("coucou_character_sweat", "character_sweat"),
            "sleep" to listOf("coucou_character_sleep", "character_sleep"),
            "working" to listOf("coucou_character_working", "character_working"),
            "thinking" to listOf("coucou_character_thinking", "character_thinking"),
            "searching" to listOf("coucou_character_searching", "character_searching"),
            "approval" to listOf("coucou_character_approval", "character_approval"),
            "question" to listOf("coucou_character_question", "character_question"),
            "error" to listOf("coucou_character_error", "character_error"),
            "finished" to listOf("coucou_character_finished", "character_finished"),
            "ratelimit" to listOf("coucou_character_ratelimit", "character_ratelimit"),
            "sleeping" to listOf("coucou_character_sleeping", "character_sleeping"),
            "dizzy" to listOf("coucou_character_dizzy", "character_dizzy")
        )

        /** Builds the palette from theme tokens, falling back to the upstream defaults. */
        fun resolvePalette(context: Context): CoucouPalette {
            val defaults = CoucouPalette()
            fun color(key: String, fallback: CoucouCharacterEngine.Rgb): CoucouCharacterEngine.Rgb {
                val names = TOKEN_NAMES[key] ?: return fallback
                for (name in names) {
                    val id = context.resources.getIdentifier(name, "color", context.packageName)
                    if (id != 0) {
                        return fromArgb(context.resources.getColor(id, null))
                    }
                }
                return fallback
            }
            val stateColors = CoucouCharacterEngine.STATE_COLORS.mapValues { (state, fallback) ->
                color(state.name.lowercase(), fallback)
            }
            return CoucouPalette(
                baseTop = color("baseTop", defaults.baseTop),
                baseBottom = color("baseBottom", defaults.baseBottom),
                ink = color("ink", defaults.ink),
                heart = color("heart", defaults.heart),
                star = color("star", defaults.star),
                spark = defaults.spark,
                sweat = color("sweat", defaults.sweat),
                sleep = color("sleep", defaults.sleep),
                stateColors = stateColors
            )
        }

        private fun fromArgb(argb: Int): CoucouCharacterEngine.Rgb = CoucouCharacterEngine.Rgb(
            Color.red(argb) / 255f,
            Color.green(argb) / 255f,
            Color.blue(argb) / 255f
        )

        private fun opaque(rgb: CoucouCharacterEngine.Rgb): Int = Color.argb(
            255,
            channel(rgb.r),
            channel(rgb.g),
            channel(rgb.b)
        )

        private fun withAlpha(rgb: CoucouCharacterEngine.Rgb, alpha: Float): Int = Color.argb(
            alpha.coerceIn(0f, 1f).times(255f).toInt(),
            channel(rgb.r),
            channel(rgb.g),
            channel(rgb.b)
        )

        private fun scaledColor(rgb: CoucouCharacterEngine.Rgb, alpha: Float): Int = withAlpha(rgb, alpha)

        private fun scaled(color: Int, alpha: Float): Int = Color.argb(
            alpha.coerceIn(0f, 1f).times(255f).toInt(),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

        private fun channel(value: Float): Int = (value * 255f).toInt().coerceIn(0, 255)

        private fun toDegrees(radians: Float): Float = radians * DEGREES

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun wrapAngle(a: Float): Float {
            val pi = Math.PI.toFloat()
            return ((a + pi) % TWO_PI + TWO_PI) % TWO_PI - pi
        }

        /** Diagnostic string for QA — asserts which state the bubble is really showing. */
        fun describeState(view: CoucouCharacterView): String =
            "state=${view.characterState.wireName} animating=${view.isAnimating}"
    }
}