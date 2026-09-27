package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.AttrRes
import kotlin.math.min

/**
 * A large, friendly "connect" button drawn on a canvas.
 *
 * It renders three states:
 *  - disconnected : a flat grey orb with a power glyph
 *  - connecting   : a spinning arc with expanding halo rings
 *  - connected    : a coloured orb with a check mark and slowly breathing rings
 *
 * All animations run on a single [ValueAnimator] so the view is cheap to keep
 * on screen while the tunnel is up.
 */
class ConnectOrbView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
) : View(context, attributeSet, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 6f * density
    }
    private val checkPath = Path()

    private var connected = false
    private var connecting = false

    private var startedColor = 0xFF2E7D32.toInt()
    private var stoppedColor = 0xFF9E9E9E.toInt()
    private var currentColor = stoppedColor

    private var pulse = 0f
    private var spin = 0f

    private var animator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        if (value) connecting = false
        animateColorTo(stateColor())
        updateAnimator()
        invalidate()
    }

    fun setConnecting(value: Boolean) {
        if (connecting == value) return
        connecting = value
        if (value) connected = false
        animateColorTo(stateColor())
        updateAnimator()
        invalidate()
    }

    fun setStateColors(started: Int, stopped: Int) {
        startedColor = started
        stoppedColor = stopped
        currentColor = stateColor()
        invalidate()
    }

    private fun stateColor(): Int = when {
        connecting -> stoppedColor
        connected -> startedColor
        else -> stoppedColor
    }

    private fun animateColorTo(target: Int) {
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofArgb(currentColor, target).apply {
            duration = 320
            addUpdateListener {
                currentColor = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    private fun updateAnimator() {
        if (connected || connecting) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1900
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        pulse = it.animatedFraction
                        spin = (spin + 9f) % 360f
                        invalidate()
                    }
                    start()
                }
            }
        } else {
            animator?.cancel()
            animator = null
            pulse = 0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        colorAnimator?.cancel()
        colorAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isClickable) {
            return false
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(160).start()
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - 26f * density

        // Expanding halo rings.
        if (connected || connecting) {
            ringPaint.strokeWidth = 1.6f * density
            for (i in 0 until 3) {
                val phase = (pulse + i / 3f) % 1f
                val r = radius * (1f + 0.42f * phase)
                ringPaint.color = withAlpha(currentColor, (60 * (1f - phase)).toInt())
                canvas.drawCircle(cx, cy, r, ringPaint)
            }
        }

        // Soft glow.
        orbPaint.shader = RadialGradient(
            cx, cy, radius * 1.45f,
            intArrayOf(withAlpha(currentColor, 70), withAlpha(currentColor, 0)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.2f, orbPaint)

        // Main orb.
        orbPaint.shader = LinearGradient(
            cx - radius, cy - radius, cx + radius, cy + radius,
            lighten(currentColor), darken(currentColor),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, orbPaint)
        orbPaint.shader = null

        iconPaint.color = 0xFFFFFFFF.toInt()

        when {
            connecting -> {
                val r = radius * 0.38f
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, spin, 270f, false, iconPaint)
            }
            connected -> {
                checkPath.reset()
                checkPath.moveTo(cx - radius * 0.34f, cy + radius * 0.02f)
                checkPath.lineTo(cx - radius * 0.09f, cy + radius * 0.27f)
                checkPath.lineTo(cx + radius * 0.36f, cy - radius * 0.24f)
                canvas.drawPath(checkPath, iconPaint)
            }
            else -> {
                val r = radius * 0.36f
                val rect = RectF(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(rect, -60f, 240f, false, iconPaint)
                canvas.drawLine(cx, cy - r, cx, cy - r * 0.15f, iconPaint)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun lighten(color: Int): Int {
        val r = ((color shr 16 and 0xFF) + 40).coerceAtMost(255)
        val g = ((color shr 8 and 0xFF) + 40).coerceAtMost(255)
        val b = ((color and 0xFF) + 40).coerceAtMost(255)
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun darken(color: Int): Int {
        val r = ((color shr 16 and 0xFF) - 20).coerceAtLeast(0)
        val g = ((color shr 8 and 0xFF) - 20).coerceAtLeast(0)
        val b = ((color and 0xFF) - 20).coerceAtLeast(0)
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}