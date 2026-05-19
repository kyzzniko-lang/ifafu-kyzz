package com.ifafu.kyzz.ui.score

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.ifafu.kyzz.R

class GpaRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val ringWidth = 4.5f * density
    private val padding = ringWidth / 2 + 1.5f * density

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
        color = resources.getColor(R.color.claude_border, null)
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        strokeCap = Paint.Cap.ROUND
        color = resources.getColor(R.color.claude_terracotta, null)
    }

    private val gpaTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = resources.getColor(R.color.claude_terracotta, null)
        typeface = resources.getFont(R.font.claude_serif)
        isFakeBoldText = true
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = resources.getColor(R.color.claude_text_hint, null)
        textSize = 9f * density
    }

    private val rect = RectF()
    private var sweepAngle = 0f
    private var displayGpa = 0f
    private var targetGpa = 0f
    private var animator: ValueAnimator? = null

    private val startAngle = 135f
    private val maxSweep = 270f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(padding, padding, w - padding, h - padding)
        gpaTextPaint.textSize = w * 0.24f
        labelTextPaint.textSize = w * 0.14f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawArc(rect, startAngle, maxSweep, false, bgPaint)
        canvas.drawArc(rect, startAngle, sweepAngle, false, progressPaint)

        val gpaText = if (displayGpa > 0) String.format("%.2f", displayGpa) else "—"
        val textY = rect.centerY() - (gpaTextPaint.descent() + gpaTextPaint.ascent()) / 2 - labelTextPaint.textSize * 0.2f
        canvas.drawText(gpaText, rect.centerX(), textY, gpaTextPaint)

        val labelY = textY + gpaTextPaint.textSize * 0.5f + labelTextPaint.textSize * 0.4f
        canvas.drawText("GPA", rect.centerX(), labelY, labelTextPaint)
    }

    fun setGpa(gpa: Float, animate: Boolean = true) {
        targetGpa = gpa.coerceIn(0f, 4f)
        val targetSweep = (targetGpa / 4f) * maxSweep

        animator?.cancel()
        if (animate) {
            animator = ValueAnimator.ofFloat(sweepAngle, targetSweep).apply {
                duration = 800
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    sweepAngle = it.animatedValue as Float
                    displayGpa = (sweepAngle / maxSweep) * 4f
                    invalidate()
                }
                start()
            }
        } else {
            sweepAngle = targetSweep
            displayGpa = targetGpa
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
