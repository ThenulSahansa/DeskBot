package com.example.deskbot

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders an organic, smooth ambient liquid glass backdrop with floating luminous chromatic orbs.
 * Produces the signature iOS / visionOS refraction and liquid glass depth underneath frosted glass cards.
 */
class IosAmbientLiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isDither = true
    }

    private var animTime = 0f
    private var animator: ValueAnimator? = null

    // Liquid orb data (normalized x, y, radius, colors)
    private class Orb(
        var baseX: Float,
        var baseY: Float,
        var radiusFactor: Float,
        val speedX: Float,
        val speedY: Float,
        val colorCenter: Int,
        val colorOuter: Int = Color.TRANSPARENT
    )

    private val orbs = arrayOf(
        // Cyan / Sky Blue liquid orb (Top-left floating)
        Orb(0.2f, 0.15f, 0.65f, 0.8f, 0.6f, Color.argb(45, 100, 210, 255)),
        // Royal Apple Blue orb (Center right)
        Orb(0.85f, 0.45f, 0.75f, -0.6f, 0.7f, Color.argb(55, 10, 132, 255)),
        // Deep Violet / Purple liquid orb (Bottom left)
        Orb(0.25f, 0.8f, 0.7f, 0.5f, -0.5f, Color.argb(45, 191, 90, 242)),
        // Subtle Indigo center highlight
        Orb(0.5f, 0.55f, 0.5f, -0.4f, -0.6f, Color.argb(30, 94, 92, 230))
    )

    init {
        startAmbientAnimation()
    }

    private fun startAmbientAnimation() {
        animator = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
            duration = 16000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                animTime = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Deep space background
        canvas.drawColor(Color.parseColor("#05060A"))

        val maxDim = maxOf(w, h)

        for (orb in orbs) {
            val offsetX = sin(animTime * orb.speedX) * 0.12f * w
            val offsetY = cos(animTime * orb.speedY) * 0.10f * h

            val cx = orb.baseX * w + offsetX
            val cy = orb.baseY * h + offsetY
            val r = maxDim * orb.radiusFactor

            orbPaint.shader = RadialGradient(
                cx, cy, r,
                orb.colorCenter,
                orb.colorOuter,
                Shader.TileMode.CLAMP
            )

            canvas.drawCircle(cx, cy, r, orbPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator?.isStarted != true) {
            animator?.start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
