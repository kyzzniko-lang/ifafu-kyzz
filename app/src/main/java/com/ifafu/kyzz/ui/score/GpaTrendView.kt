package com.ifafu.kyzz.ui.score

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.ifafu.kyzz.R

class GpaTrendView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Point(val label: String, val value: Float)

    private var points: List<Point> = emptyList()

    private val terracotta by lazy { resources.getColor(R.color.claude_terracotta, null) }
    private val textSecondary by lazy { resources.getColor(R.color.claude_text_secondary, null) }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(12f)
        textAlign = Paint.Align.CENTER
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = spToPx(13f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A000000
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    fun setData(data: List<Point>) {
        points = data
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (150 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        linePaint.color = terracotta
        dotPaint.color = terracotta
        textPaint.color = textSecondary
        valuePaint.color = terracotta

        super.onDraw(canvas)
        if (points.size < 2) {
            textPaint.textSize = spToPx(13f)
            canvas.drawText("至少需要两个学期的数据", width / 2f, height / 2f, textPaint)
            return
        }

        val padLeft = 60f
        val padRight = 40f
        val padTop = 50f
        val padBottom = 70f
        val chartW = width - padLeft - padRight
        val chartH = height - padTop - padBottom

        val minVal = (points.minOf { it.value } - 0.2f).coerceAtLeast(0f)
        val maxVal = (points.maxOf { it.value } + 0.2f).coerceAtMost(4.5f)
        val range = (maxVal - minVal).coerceAtLeast(0.01f)

        for (i in 0..4) {
            val y = padTop + chartH * (1 - i / 4f)
            canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
        }

        val path = Path()
        val stepX = chartW / (points.size - 1)

        for ((i, pt) in points.withIndex()) {
            val x = padLeft + i * stepX
            val y = padTop + chartH * (1 - (pt.value - minVal) / range)

            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)

            canvas.drawCircle(x, y, 8f, dotPaint)
            canvas.drawText(pt.label, x, height - 10f, textPaint)
            canvas.drawText(String.format("%.2f", pt.value), x, y - 20f, valuePaint)
        }

        canvas.drawPath(path, linePaint)
    }
}
