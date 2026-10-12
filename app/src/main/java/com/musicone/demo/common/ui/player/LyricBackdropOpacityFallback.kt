package com.musicone.demo

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer

internal const val LYRIC_BACKDROP_FALLBACK_ALPHA = .18f

/** 复用模糊带的实际边界，以透明度过渡保留歌词与背景的连续遮挡关系。 */
internal fun DrawScope.drawLyricBackdropOpacityFallback(
    source: GraphicsLayer,
    top: Float,
    transitionStart: Float,
    transitionEnd: Float,
    paint: Paint,
) {
    val start = transitionStart.coerceIn(0f, size.height)
    val end = transitionEnd.coerceAtLeast(start + 1f)
    val mask = Brush.verticalGradient(
        listOf(Color.White, Color.White.copy(alpha = LYRIC_BACKDROP_FALLBACK_ALPHA)),
        startY = start, endY = end,
    )
    clipRect(top = top) {
        clipRect(bottom = start) { drawLayer(source) }
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, start, size.width, size.height), paint)
            clipRect(top = start) { drawLayer(source) }
            drawRect(mask, blendMode = BlendMode.DstIn)
            canvas.restore()
        }
    }
}
