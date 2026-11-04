package com.musicone

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 状态栏下方补足过渡距离，不重复添加布局留白；遮罩始终固定在视口。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun Modifier.tabletLyricsTopFade(fadeBottom: Boolean = false, bottomFadeHeight: Dp? = null,
    singlePass: Boolean = false, enabled: Boolean = true, stableInsets: Boolean = false,
    drawingWindow: PhoneLyricsDrawingWindow = PhoneLyricsDrawingWindow.None,
    fadeTurn: PhoneLyricsFadeTurn? = null, landscape: Boolean = true,
    orientationChanging: () -> Boolean = { false }, readingPivot: () -> Float = { 0f },
    windowMotionOffset: (() -> Float)? = null): Modifier {
    val statusBarHeight = (if (stableInsets) WindowInsets.statusBarsIgnoringVisibility else WindowInsets.statusBars)
        .getTop(LocalDensity.current)
    if (singlePass && fadeTurn != null) return phoneLyricsTurnFade(enabled, fadeBottom, bottomFadeHeight,
        statusBarHeight, drawingWindow, fadeTurn, landscape, orientationChanging, readingPivot, windowMotionOffset)
    val host = if (singlePass) graphicsLayer {
        compositingStrategy = if (enabled) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    } else this
    return host.drawWithCache {
        if (!enabled) return@drawWithCache onDrawWithContent { drawContent() }
        val viewportHeight = size.height - drawingWindow.top - drawingWindow.bottom
        val fadeHeight = minOf(statusBarHeight + 24.dp.toPx(), viewportHeight / if (fadeBottom) 2f else 1f).coerceAtLeast(1f)
        val bottomHeight = minOf(bottomFadeHeight?.toPx() ?: fadeHeight,
            (viewportHeight - fadeHeight).coerceAtLeast(0f))
        val mask = Brush.verticalGradient(
            listOf(Color.Transparent, Color.White), startY = 0f, endY = fadeHeight,
        )
        val bottomMask = if (fadeBottom) Brush.verticalGradient(
            listOf(Color.White, Color.Transparent), startY = 0f, endY = bottomHeight,
        ) else null
        val paint = Paint()
        onDrawWithContent {
            if (singlePass) {
                // 同一视口只执行一次文字绘制，正文、翻译与逐行门控使用相同的裁剪原点。
                val p = drawingWindow.progress()
                val top = drawingWindow.top * p
                val bottom = size.height - drawingWindow.bottom * p
                clipRect(top = top, bottom = bottom) { this@onDrawWithContent.drawContent() }
                translate(top = top) {
                    drawRect(mask, size = Size(size.width, fadeHeight), blendMode = BlendMode.DstIn)
                }
                if (bottomMask != null) translate(top = bottom - bottomHeight) {
                    drawRect(bottomMask, size = Size(size.width, bottomHeight), blendMode = BlendMode.DstIn)
                }
                return@onDrawWithContent
            }
            val bottomStart = if (fadeBottom) size.height - bottomHeight else size.height
            drawIntoCanvas { canvas ->
                clipRect(top = fadeHeight, bottom = bottomStart) { this@onDrawWithContent.drawContent() }
                canvas.saveLayer(Rect(0f, 0f, size.width, fadeHeight), paint)
                clipRect(bottom = fadeHeight) {
                    this@onDrawWithContent.drawContent()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
                canvas.restore()
                // 手机内部横屏复用同一遮罩，在底边也平滑退隐，避免直接切断文字。
                if (bottomMask != null) {
                    canvas.saveLayer(Rect(0f, bottomStart, size.width, size.height), paint)
                    clipRect(top = bottomStart) {
                        this@onDrawWithContent.drawContent()
                        translate(top = bottomStart) {
                            drawRect(bottomMask, size = Size(size.width, bottomHeight), blendMode = BlendMode.DstIn)
                        }
                    }
                    canvas.restore()
                }
            }
        }
    }
}
