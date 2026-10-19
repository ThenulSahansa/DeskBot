package com.example.deskbot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Custom View simulating the iconic iOS NameDrop / AirDrop energized fluid wave animation.
 * Features:
 * - Chromatic fluid shockwave rings emanating from the device contact / connection point
 * - Intense liquid plasma glow & iridescent color shifts (Cyan, Royal Blue, Violet, Magenta, Pure White)
 * - Energized stardust spark particles with velocity and harmonic oscillation
 * - Native haptic feedback sync
 */
class IosNameDropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var isPlaying = false
    private var progress = 0f
    private var originX = 0f
    private var originY = 0f
    private var maxRadius = 0f

    // Wave Paints
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        isDither = true
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val starPath = Path()

    // Particle pool
    private class Particle(
        val angle: Float,
        val speed: Float,
        val size: Float,
        val color: Int,
        val spinSpeed: Float
    ) {
        var x = 0f
        var y = 0f
        var alpha = 1f
        var rotation = 0f
    }

    private val particles = mutableListOf<Particle>()
    private var animator: ValueAnimator? = null

    init {
        visibility = GONE
    }

    fun triggerNameDrop(customOriginX: Float? = null, customOriginY: Float? = null, onComplete: (() -> Unit)? = null) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) {
            post { triggerNameDrop(customOriginX, customOriginY, onComplete) }
            return
        }

        originX = customOriginX ?: (w / 2f)
        originY = customOriginY ?: 0f // Top contact edge like iOS NameDrop
        maxRadius = hypot(maxOf(originX, w - originX), maxOf(originY, h - originY)) * 1.25f

        // Spawn energized particle bursts
        particles.clear()
        val rnd = Random(System.currentTimeMillis())
        val colors = intArrayOf(
            Color.parseColor("#FFFFFF"),
            Color.parseColor("#64D2FF"),
            Color.parseColor("#0A84FF"),
            Color.parseColor("#BF5AF2"),
            Color.parseColor("#5AC8FA"),
            Color.parseColor("#FF2D55")
        )

        for (i in 0 until 45) {
            val angle = rnd.nextFloat() * Math.PI.toFloat() * 1.2f - (Math.PI.toFloat() * 0.1f) // Downward arc
            val speed = 0.5f + rnd.nextFloat() * 0.85f
            val size = (3f + rnd.nextFloat() * 6f) * resources.displayMetrics.density
            val color = colors[rnd.nextInt(colors.size)]
            val spin = (rnd.nextFloat() - 0.5f) * 8f
            particles.add(Particle(angle, speed, size, color, spin))
        }

        // Haptic feedback
        performHapticTap()

        animator?.cancel()
        visibility = VISIBLE
        isPlaying = true
        progress = 0f

        // iOS spring-style easing curve
        val iosSpringInterpolator = PathInterpolator(0.18f, 0.9f, 0.22f, 1.0f)

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1450L
            interpolator = iosSpringInterpolator
            addUpdateListener { anim ->
                progress = anim.animatedValue as Float
                updateParticles(progress)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isPlaying = false
                    visibility = GONE
                    onComplete?.invoke()
                }
            })
            start()
        }
    }

    private fun updateParticles(p: Float) {
        val density = resources.displayMetrics.density
        for (pt in particles) {
            val dist = p * maxRadius * pt.speed * 0.9f
            pt.x = originX + cos(pt.angle) * dist
            pt.y = originY + sin(pt.angle) * dist
            pt.rotation += pt.spinSpeed

            // Particle alpha curve: fast emergence, high brightness, soft exponential fade
            val pAlpha = when {
                p < 0.15f -> p / 0.15f
                p > 0.6f -> (1f - (p - 0.6f) / 0.4f).coerceAtLeast(0f)
                else -> 1f
            }
            pt.alpha = pAlpha
        }
    }

    private fun performHapticTap() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 45), intArrayOf(0, 180, 0, 255), -1))
            } else {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Exception) {}
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isPlaying) return

        val p = progress
        val density = resources.displayMetrics.density

        // 1. Ambient Energetic Liquid Flare Bloom
        val bloomRadius = (maxRadius * (p * 0.95f)).coerceAtLeast(20f)
        val bloomAlpha = if (p < 0.4f) (p / 0.4f) * 0.45f else (1f - (p - 0.4f) / 0.6f) * 0.45f

        if (bloomAlpha > 0.01f) {
            val bloomColorInner = Color.argb((bloomAlpha * 255).toInt(), 100, 210, 255)
            val bloomColorMid = Color.argb((bloomAlpha * 180).toInt(), 10, 132, 255)
            val bloomColorOuter = Color.TRANSPARENT

            glowPaint.shader = RadialGradient(
                originX, originY, bloomRadius,
                intArrayOf(bloomColorInner, bloomColorMid, bloomColorOuter),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(originX, originY, bloomRadius, glowPaint)
        }

        // 2. Chromatic Liquid Shockwave Rings (The NameDrop Ripples)
        // Primary Radiant Wave
        drawFluidRing(canvas, p, 1.0f, 26f * density, Color.parseColor("#FFFFFF"), Color.parseColor("#64D2FF"))

        // Secondary Royal Blue Resonance Wave
        drawFluidRing(canvas, (p - 0.08f).coerceAtLeast(0f), 0.92f, 18f * density, Color.parseColor("#64D2FF"), Color.parseColor("#0A84FF"))

        // Tertiary Magenta / Violet Plasma Ripple
        drawFluidRing(canvas, (p - 0.16f).coerceAtLeast(0f), 0.85f, 14f * density, Color.parseColor("#BF5AF2"), Color.parseColor("#5E5CE6"))

        // Fourth Subtle Echo Ripple
        drawFluidRing(canvas, (p - 0.24f).coerceAtLeast(0f), 0.76f, 10f * density, Color.parseColor("#5AC8FA"), Color.parseColor("#0A84FF"))

        // 3. Stardust & Spark Particles
        for (pt in particles) {
            if (pt.alpha <= 0.01f) continue

            val c = pt.color
            val alphaInt = (pt.alpha * Color.alpha(c)).toInt().coerceIn(0, 255)
            particlePaint.color = Color.argb(alphaInt, Color.red(c), Color.green(c), Color.blue(c))

            // Draw glowing 4-point star for larger particles, circle for small
            if (pt.size > 8f * density) {
                drawStar(canvas, pt.x, pt.y, pt.size, pt.rotation, particlePaint)
            } else {
                canvas.drawCircle(pt.x, pt.y, pt.size / 2f, particlePaint)
            }
        }
    }

    private fun drawFluidRing(
        canvas: Canvas,
        prog: Float,
        speedMultiplier: Float,
        baseStrokeWidth: Float,
        colorInner: Int,
        colorOuter: Int
    ) {
        if (prog <= 0.001f || prog >= 0.999f) return

        val r = prog * maxRadius * speedMultiplier
        val alphaProg = if (prog < 0.2f) {
            prog / 0.2f
        } else {
            1f - ((prog - 0.2f) / 0.8f)
        }
        val alpha = (alphaProg * 255).toInt().coerceIn(0, 255)

        // Fluid stroke wobble
        val wobble = sin(prog * Math.PI.toFloat() * 3f) * 4f
        val strokeW = (baseStrokeWidth * (1f - prog * 0.6f) + wobble).coerceAtLeast(1f)

        wavePaint.strokeWidth = strokeW
        wavePaint.color = Color.argb(alpha, Color.red(colorInner), Color.green(colorInner), Color.blue(colorInner))

        canvas.drawCircle(originX, originY, r, wavePaint)
    }

    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, size: Float, rotationDeg: Float, paint: Paint) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotationDeg)

        val half = size / 2f
        val quarter = size / 6f

        starPath.reset()
        starPath.moveTo(0f, -half)
        starPath.quadTo(0f, 0f, half, 0f)
        starPath.quadTo(0f, 0f, 0f, half)
        starPath.quadTo(0f, 0f, -half, 0f)
        starPath.quadTo(0f, 0f, 0f, -half)
        starPath.close()

        canvas.drawPath(starPath, paint)
        canvas.restore()
    }
}
