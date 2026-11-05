package com.musicone

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LineBackgroundSpan
import android.text.style.MetricAffectingSpan
import kotlin.math.max
import kotlin.math.min

/** 圆角行内代码保留原生换行与选择，不将整段替换成不可断行的图片或标签。 */
internal class AiInlineCodeSpan(private val ink: Int, private val background: Int, private val density: Float) :
    MetricAffectingSpan(), LineBackgroundSpan {
    override fun updateMeasureState(paint: TextPaint) {
        paint.typeface = Typeface.create("monospace", paint.typeface?.style ?: Typeface.NORMAL)
        paint.textSize *= .9f
    }
    override fun updateDrawState(paint: TextPaint) {
        updateMeasureState(paint)
        paint.color = ink
        paint.bgColor = 0
    }
    override fun drawBackground(canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int,
        bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
        val styled = text as? Spanned ?: return
        val from = max(start, styled.getSpanStart(this))
        val to = min(end, styled.getSpanEnd(this))
        if (from >= to) return
        val measure = TextPaint(paint)
        val x = left + Layout.getDesiredWidth(text, start, from, measure)
        val width = Layout.getDesiredWidth(text, from, to, measure)
        val oldColor = paint.color
        val oldStyle = paint.style
        paint.color = background
        paint.style = Paint.Style.FILL
        val radius = 4f * density
        canvas.drawRoundRect((x - 2f * density).coerceAtLeast(left.toFloat()), top.toFloat(),
            (x + width + 2f * density).coerceAtMost(right.toFloat()), bottom.toFloat(), radius, radius, paint)
        paint.color = oldColor
        paint.style = oldStyle
    }
}
