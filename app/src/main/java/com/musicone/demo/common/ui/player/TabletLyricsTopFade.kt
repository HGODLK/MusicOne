package com.musicone.demo

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/** 状态栏下方补足过渡距离，不重复添加布局留白；遮罩始终固定在视口。 */
@Composable
internal fun Modifier.tabletLyricsTopFade(): Modifier {
    val statusBarHeight = WindowInsets.statusBars.getTop(LocalDensity.current)
    return drawWithCache {
        val fadeHeight = minOf(statusBarHeight + 24.dp.toPx(), size.height).coerceAtLeast(1f)
        val mask = Brush.verticalGradient(
            listOf(Color.Transparent, Color.White), startY = 0f, endY = fadeHeight,
        )
        val paint = Paint()
        onDrawWithContent {
            drawIntoCanvas { canvas ->
                clipRect(top = fadeHeight) { this@onDrawWithContent.drawContent() }
                canvas.saveLayer(Rect(0f, 0f, size.width, fadeHeight), paint)
                clipRect(bottom = fadeHeight) {
                    this@onDrawWithContent.drawContent()
                    drawRect(mask, blendMode = BlendMode.DstIn)
                }
                canvas.restore()
            }
        }
    }
}
