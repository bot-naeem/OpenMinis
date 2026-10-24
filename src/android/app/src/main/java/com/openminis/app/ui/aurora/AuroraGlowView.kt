package com.openminis.app.ui.aurora

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.Build
import android.util.AttributeSet
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator

/**
 * Fullscreen pass-through overlay view that renders an ethereal Aurora Edge Glow
 * along the perimeter of the smartphone display.
 *
 * Configured with FLAG_NOT_TOUCHABLE so all touches fall through to the underlying app.
 * Provides instant 0-alpha switching for Shutter Blink during screenshots.
 */
class AuroraGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val borderPath = Path()
    private val borderRect = RectF()
    private val shaderMatrix = Matrix()

    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var currentState = AuroraState.EXECUTING

    @Volatile
    private var _isShutterHidden: Boolean = false
    val isShutterHidden: Boolean get() = _isShutterHidden

    init {
        fitsSystemWindows = false
        @Suppress("DEPRECATION")
        systemUiVisibility = (
            SYSTEM_UI_FLAG_LAYOUT_STABLE or
            SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
        // Hardware acceleration for fluid 60fps sweep rendering
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // Prevent system from applying insets as padding so the view remains truly edge-to-edge
        return insets
    }

    fun setState(state: AuroraState) {
        if (currentState == state) return
        currentState = state
        restartAnimation()
        invalidate()
    }

    fun setShutterHidden(hidden: Boolean) {
        _isShutterHidden = hidden
        alpha = if (hidden) 0f else 1f
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimation()
    }

    private fun restartAnimation() {
        stopAnimation()
        if (currentState == AuroraState.IDLE) return

        val animDuration = when (currentState) {
            AuroraState.EXECUTING -> 2400L // Fluid, continuous rotating wave
            AuroraState.THINKING -> 3200L  // Slower contemplative breathing
            AuroraState.PAUSED -> 4500L    // Calm, resting glow
            AuroraState.COMPLETED -> 1200L
            AuroraState.LISTENING -> 1600L // Responsive, vibrant listening ripple
            AuroraState.INTERVENTION -> 4000L // Gentle amber intervention beacon
            AuroraState.IDLE -> 1000L
        }

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = animDuration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                phase = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isShutterHidden || currentState == AuroraState.IDLE) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val strokeWidth = 3f * density
        val glowWidth = 7f * density
        val radii = getScreenCornerRadii()

        val halfStroke = strokeWidth / 2f
        borderRect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        borderPath.reset()
        borderPath.addRoundRect(borderRect, radii, Path.Direction.CW)

        val cx = w / 2f
        val cy = h / 2f
        val colors = getColorsForState(currentState)

        val shader = SweepGradient(cx, cy, colors, null)
        shaderMatrix.reset()
        shaderMatrix.postRotate(phase * 360f, cx, cy)
        shader.setLocalMatrix(shaderMatrix)

        // 1. Draw outer soft diffused glow
        glowPaint.strokeWidth = glowWidth
        glowPaint.shader = shader
        glowPaint.alpha = when (currentState) {
            AuroraState.THINKING -> {
                val pulse = (Math.sin(phase * 2 * Math.PI) * 0.5 + 0.5).toFloat()
                (40 + pulse * 60).toInt().coerceIn(0, 255)
            }
            AuroraState.PAUSED -> 50
            else -> 80
        }
        canvas.drawPath(borderPath, glowPaint)

        // 2. Draw crisp core neon stroke
        strokePaint.strokeWidth = strokeWidth
        strokePaint.shader = shader
        strokePaint.alpha = when (currentState) {
            AuroraState.THINKING -> {
                val pulse = (Math.sin(phase * 2 * Math.PI) * 0.5 + 0.5).toFloat()
                (140 + pulse * 100).toInt().coerceIn(0, 255)
            }
            AuroraState.PAUSED -> 180
            else -> 240
        }
        canvas.drawPath(borderPath, strokePaint)
    }

    private fun getScreenCornerRadii(): FloatArray {
        val defaultR = 28f * resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = rootWindowInsets
            if (insets != null) {
                val tl = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: defaultR
                val tr = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius?.toFloat() ?: defaultR
                val br = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?.toFloat() ?: defaultR
                val bl = insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.toFloat() ?: defaultR
                return floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
            }
        }
        return floatArrayOf(defaultR, defaultR, defaultR, defaultR, defaultR, defaultR, defaultR, defaultR)
    }

    private fun getColorsForState(state: AuroraState): IntArray {
        return when (state) {
            AuroraState.EXECUTING -> intArrayOf(
                Color.parseColor("#00F5D4"), // Neon Cyan
                Color.parseColor("#7B2CBF"), // Electric Violet
                Color.parseColor("#F72585"), // Bright Magenta
                Color.parseColor("#4CC9F0"), // Azure
                Color.parseColor("#00F5D4")  // Wrap to Neon Cyan
            )
            AuroraState.THINKING -> intArrayOf(
                Color.parseColor("#3A0CA3"), // Deep Indigo
                Color.parseColor("#7209B7"), // Royal Purple
                Color.parseColor("#4361EE"), // Slate Blue
                Color.parseColor("#3A0CA3")  // Deep Indigo
            )
            AuroraState.PAUSED -> intArrayOf(
                Color.parseColor("#FF9E00"), // Amber
                Color.parseColor("#FFD000"), // Gold
                Color.parseColor("#FF6000"), // Orange
                Color.parseColor("#FF9E00")
            )
            AuroraState.COMPLETED -> intArrayOf(
                Color.parseColor("#06D6A0"), // Emerald Green
                Color.parseColor("#2EC4B6"), // Mint Green
                Color.parseColor("#06D6A0")
            )
            AuroraState.LISTENING -> intArrayOf(
                Color.parseColor("#48CAE4"), // Sky Cyan
                Color.parseColor("#7209B7"), // Electric Purple
                Color.parseColor("#06D6A0"), // Neon Seafoam
                Color.parseColor("#48CAE4")
            )
            AuroraState.INTERVENTION -> intArrayOf(
                Color.parseColor("#FF5400"), // Flame Orange
                Color.parseColor("#FF9E00"), // Amber
                Color.parseColor("#FFD000"), // Warm Gold
                Color.parseColor("#FF5400")
            )
            AuroraState.IDLE -> intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT
            )
        }
    }
}
