package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 横屏和转屏期间的歌词上下边缘渐隐遮罩：复用歌词绘制缓存，逐字和逐行动画仍由子图层独立更新。 */
@Composable
internal fun Modifier.phoneLyricsTurnFade(enabled: Boolean, fadeBottom: Boolean, bottomFadeHeight: Dp?,
    statusBarHeight: Int, drawingWindow: PhoneLyricsDrawingWindow, fadeTurn: PhoneLyricsFadeTurn,
    landscape: Boolean, orientationChanging: () -> Boolean, readingPivot: () -> Float,
    windowMotionOffset: (() -> Float)? = null): Modifier {
    val contentEpoch = LocalPlayerLyricsContent.current?.drawEpoch
    // 用独立 graphicsLayer 包裹以便外层引用，默认自动合成不会把整屏离屏栅格化。
    return graphicsLayer().lyricDrawWithCache {
        if (!enabled) return@lyricDrawWithCache onDrawWithContent { drawContent() }
        val viewportHeight = size.height - drawingWindow.top - drawingWindow.bottom
        val fadeHeight = minOf(statusBarHeight + 24.dp.toPx(), viewportHeight / if (fadeBottom) 2f else 1f)
            .coerceAtLeast(1f)
        val bottomHeight = minOf(bottomFadeHeight?.toPx() ?: fadeHeight,
            (viewportHeight - fadeHeight).coerceAtLeast(0f))
        // 用独立图层记录歌词内容引用，不额外分配整屏离屏纹理。
        val source = obtainGraphicsLayer()
        val paint = Paint()
        val topRemoval = Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = 0f, endY = 1f)
        val bottomRemoval = Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = 0f, endY = 1f)
        var recordedEpoch = Long.MIN_VALUE
        onDrawWithContent {
            val p = drawingWindow.progress()
            val shift = windowMotionOffset?.invoke()?.coerceAtLeast(0f) ?: 0f
            val viewport = drawingWindow.viewport(size.height, shift, p)
            val frame = fadeTurn.frame(PhoneLyricsFadeFrame(viewport.top,
                viewport.bottom, fadeHeight, bottomHeight,
                readingPivot() - shift, if (landscape) 1f else 0f), orientationChanging(), p)
            val epoch = contentEpoch?.revision ?: 0L
            if (recordedEpoch != epoch) {
                source.record { this@onDrawWithContent.drawContent() }
                recordedEpoch = epoch
            }
            drawPhoneLyricsTurnFade(frame, fadeBottom, paint, topRemoval, bottomRemoval) { drawLayer(source) }
        }
    }
}

/** 只对上下渐隐带开 saveLayer 做 DstOut 渐隐，中间清晰区域直接绘制原图层以省开销。 */
internal fun DrawScope.drawPhoneLyricsTurnFade(frame: PhoneLyricsFadeFrame, fadeBottom: Boolean,
    paint: Paint, topRemoval: Brush, bottomRemoval: Brush, drawSource: DrawScope.() -> Unit) {
    val strength = frame.strength.coerceIn(0f, 1f)
    if (strength == 0f) { drawSource(); return }
    val upper = frame.top.coerceIn(0f, size.height)
    val lower = frame.bottom.coerceIn(0f, size.height)
    // 只有遮罩强度达到 1 时才能跳过上下边界外的区域；渐变过渡期间仍需按权重画出来。
    val visibleTop = if (strength == 1f) upper else 0f
    val visibleBottom = if (strength == 1f) lower else size.height
    if (visibleBottom <= visibleTop) return
    val topEnd = (frame.top + frame.topHeight.coerceAtLeast(1f)).coerceIn(visibleTop, visibleBottom)
    val bottomStart = (frame.bottom - if (fadeBottom) frame.bottomHeight.coerceAtLeast(0f) else 0f)
        .coerceIn(visibleTop, visibleBottom)
    fun masked(start: Float, end: Float) {
        if (end <= start) return
        clipRect(top = start, bottom = end) {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, start, size.width, end), paint)
                try {
                    drawSource()
                    drawRect(Color.White, size = Size(size.width, upper), alpha = strength, blendMode = BlendMode.DstOut)
                    drawRect(Color.White, topLeft = Offset(0f, lower), size = Size(size.width, size.height - lower),
                        alpha = strength, blendMode = BlendMode.DstOut)
                    translate(top = frame.top) {
                        scale(1f, frame.topHeight.coerceAtLeast(1f), pivot = Offset.Zero) {
                            drawRect(topRemoval, size = Size(size.width, 1f), alpha = strength, blendMode = BlendMode.DstOut)
                        }
                    }
                    if (fadeBottom && frame.bottomHeight > 0f) translate(top = frame.bottom - frame.bottomHeight) {
                        scale(1f, frame.bottomHeight.coerceAtLeast(1f), pivot = Offset.Zero) {
                            drawRect(bottomRemoval, size = Size(size.width, 1f), alpha = strength, blendMode = BlendMode.DstOut)
                        }
                    }
                } finally { canvas.restore() }
            }
        }
    }
    // 如果视口太矮导致上下渐隐区重叠，合并成一个 saveLayer 统一处理。
    if (topEnd >= bottomStart) masked(visibleTop, visibleBottom) else {
        masked(visibleTop, topEnd)
        clipRect(top = topEnd, bottom = bottomStart) { drawSource() }
        masked(bottomStart, visibleBottom)
    }
}
