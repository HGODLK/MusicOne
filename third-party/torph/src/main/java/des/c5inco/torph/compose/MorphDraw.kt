package des.c5inco.torph.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import des.c5inco.torph.core.SegmentKind

/** Per-frame draw: N cached `drawText` calls, no measurement, no allocation on the hot path. */
internal fun TextMorphState.draw(scope: DrawScope) = with(scope) {
    frameTick.intValue // subscribe to the frame loop
    val list = live
    val debug = options.debug
    val clipSpec = options.clip
    val color = drawColor?.invoke() ?: Color.Unspecified
    val shadow = drawShadow?.invoke()
    var i = 0
    val n = list.size
    while (i < n) {
        val ls = list[i]
        i++
        if (ls.segment.kind == SegmentKind.NEWLINE) continue
        val alpha = ls.alpha.value
        if (alpha <= 0.001f && !debug) continue
        val x = ls.x.value
        val y = ls.y.value
        val strip = ls.strip
        if (strip != null) {
            val h = ls.cell.height
            val padY = h * clipSpec.overflow
            val softClip = ls.softClip(x - ls.width * 0.5f, y - padY, x + ls.width * 1.5f, y + h + padY, padY + h * clipSpec.inset)
            drawWithSoftClip(softClip) {
                drawStrip(ls, strip, ls.stripProgress.value, x, y, alpha, color, shadow)
            }
        } else {
            val clip = ls.clip
            if (clip != null) {
                val padY = clip.height * clipSpec.overflow
                val softClip = ls.softClip(
                    clip.left - ls.width * 0.5f, clip.top - padY, clip.right + ls.width * 0.5f, clip.bottom + padY,
                    padY + clip.height * clipSpec.inset,
                )
                drawWithSoftClip(softClip) {
                    drawSegment(ls, x, y, ls.scale.value, alpha, color, shadow)
                }
            } else {
                drawSegment(ls, x, y, ls.scale.value, alpha, color, shadow)
            }
        }
        if (debug) drawDebug(textMeasurer, ls, x, y)
    }
}

private fun DrawScope.drawSegment(ls: LiveSegment, x: Float, y: Float, sc: Float, alpha: Float,
    color: Color, shadow: Shadow?) {
    if (sc == 1f) {
        drawText(ls.layout, color = color, topLeft = Offset(x, y), alpha = alpha, shadow = shadow)
    } else {
        scale(sc, sc, pivot = Offset(x + ls.width / 2f, y + ls.height / 2f)) {
            drawText(ls.layout, color = color, topLeft = Offset(x, y), alpha = alpha, shadow = shadow)
        }
    }
}

/** Odometer: digit k sits k - progress cells away from the target, in the roll direction. */
private fun DrawScope.drawStrip(ls: LiveSegment, strip: List<TextLayoutResult>, progress: Float, x: Float, y: Float, alpha: Float,
    color: Color, shadow: Shadow?) {
    val h = ls.cell.height
    val centerX = x + ls.width / 2f
    val first = (progress - 1f).toInt().coerceAtLeast(0)
    val last = (progress + 2f).toInt().coerceAtMost(strip.size - 1)
    var k = first
    while (k <= last) {
        val layout = strip[k]
        val dy = y + ls.stripRoll * (k - progress) * h
        drawText(layout, color = color, topLeft = Offset(centerX - layout.size.width / 2f, dy), alpha = alpha,
            shadow = shadow)
        k++
    }
}

private val layerPaint = Paint()

/** A layer's bounds and the vertical gradient that fades its top and bottom [fadeHeight] out. */
internal class SoftClip(val bounds: Rect, val fadeHeight: Float, val mask: Brush)

/**
 * The soft clip for these bounds, reusing the segment's previous one when nothing moved (the usual
 * case: a roll's cell holds still), so the gradient and its shader aren't rebuilt every frame.
 */
private fun LiveSegment.softClip(left: Float, top: Float, right: Float, bottom: Float, fadeHeight: Float): SoftClip {
    val cached = softClip
    if (cached != null && cached.fadeHeight == fadeHeight && cached.bounds.left == left &&
        cached.bounds.top == top && cached.bounds.right == right && cached.bounds.bottom == bottom
    ) return cached
    val fadeFraction = (fadeHeight / (bottom - top)).coerceIn(0.01f, 0.49f)
    val mask = Brush.verticalGradient(
        0.0f to Color.Transparent,
        fadeFraction to Color.Black,
        (1f - fadeFraction) to Color.Black,
        1.0f to Color.Transparent,
        startY = top,
        endY = bottom,
    )
    return SoftClip(Rect(left, top, right, bottom), fadeHeight, mask).also { softClip = it }
}

private inline fun DrawScope.drawWithSoftClip(
    softClip: SoftClip,
    drawContent: DrawScope.() -> Unit,
) {
    drawIntoCanvas { canvas ->
        val bounds = softClip.bounds
        canvas.saveLayer(bounds, layerPaint)
        drawContent()
        drawRect(
            brush = softClip.mask,
            topLeft = bounds.topLeft,
            size = bounds.size,
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
}

private val debugStyle = TextStyle(fontSize = 8.sp)
private val enterColor = Color(0xFF2E7D32)
private val exitColor = Color(0xFFC62828)
private val persistColor = Color(0xFF1565C0)
private val rollColor = Color(0xFFEF6C00)

private fun DrawScope.drawDebug(textMeasurer: TextMeasurer, ls: LiveSegment, x: Float, y: Float) {
    val color = when {
        ls.exiting -> exitColor
        ls.entering -> enterColor
        ls.segment.kind == SegmentKind.DIGIT -> rollColor
        else -> persistColor
    }
    val offset = Offset(x, y)
    drawRect(color, topLeft = offset, size = Size(ls.width, ls.height), style = Stroke(1f), alpha = 0.9f)
    val label = textMeasurer.measure(AnnotatedString(debugLabel(ls)), debugStyle, maxLines = 1)
    val labelTop = Offset(x, y - label.size.height)
    drawRect(color, topLeft = labelTop, size = Size(label.size.width.toFloat(), label.size.height.toFloat()), alpha = 0.85f)
    drawText(label, color = Color.White, topLeft = labelTop)
}

private fun debugLabel(ls: LiveSegment): String {
    val s = ls.segment
    return buildString {
        append(s.id)
        if (s.place != null) append(" p").append(s.place)
    }
}
