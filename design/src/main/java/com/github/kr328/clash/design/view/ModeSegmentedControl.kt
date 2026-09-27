package com.github.kr328.clash.design.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.annotation.AttrRes

/**
 * A small iOS/Material style segmented control with a sliding indicator.
 *
 * Used on the simplified home screen to switch between rule / global / direct
 * routing modes without opening the full proxy page.
 */
class ModeSegmentedControl @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0,
) : View(context, attributeSet, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }

    private var items: List<CharSequence> = emptyList()
    private var selected = 0
    private var position = 0f
    private var animator: ValueAnimator? = null

    private var backgroundColor = 0xFFE0E0E0.toInt()
    private var indicatorColor = 0xFF1976D2.toInt()
    private var selectedTextColor = 0xFFFFFFFF.toInt()
    private var normalTextColor = 0xFF424242.toInt()

    private var onSelected: ((Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun configure(
        backgroundColor: Int,
        indicatorColor: Int,
        selectedTextColor: Int,
        normalTextColor: Int,
    ) {
        this.backgroundColor = backgroundColor
        this.indicatorColor = indicatorColor
        this.selectedTextColor = selectedTextColor
        this.normalTextColor = normalTextColor
        invalidate()
    }

    fun setItems(items: List<CharSequence>) {
        this.items = items
        invalidate()
    }

    fun setOnSelectedListener(listener: (Int) -> Unit) {
        onSelected = listener
    }

    fun setSelection(index: Int, animate: Boolean = true) {
        if (index !in items.indices) return
        if (selected == index) return

        selected = index

        if (animate) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(position, index.toFloat()).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            position = index.toFloat()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val defaultHeight = (44f * density).toInt() + paddingTop + paddingBottom
        val height = View.resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && items.isNotEmpty()) {
            val segWidth = (width - paddingLeft - paddingRight).toFloat() / items.size
            val index = ((event.x - paddingLeft) / segWidth).toInt().coerceIn(0, items.size - 1)
            if (index != selected) {
                setSelection(index)
                onSelected?.invoke(index)
            }
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (items.isEmpty()) return

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val radius = (bottom - top) / 2f
        val inset = 3f * density

        backgroundPaint.color = backgroundColor
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, backgroundPaint)

        val segWidth = (right - left) / items.size
        val indicatorRect = RectF(
            left + inset + position * segWidth,
            top + inset,
            left + inset + (position + 1) * segWidth - inset * 2f,
            bottom - inset
        )
        val indicatorRadius = indicatorRect.height() / 2f
        indicatorPaint.color = indicatorColor
        canvas.drawRoundRect(indicatorRect, indicatorRadius, indicatorRadius, indicatorPaint)

        val centerY = (top + bottom) / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        items.forEachIndexed { index, item ->
            textPaint.color = if (index == selected) selectedTextColor else normalTextColor
            val cx = left + segWidth * (index + 0.5f)
            canvas.drawText(item.toString(), cx, centerY, textPaint)
        }
    }
}