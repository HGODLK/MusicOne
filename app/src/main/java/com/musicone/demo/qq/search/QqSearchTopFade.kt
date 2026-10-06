package com.musicone.demo

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal fun searchTopWhiteAlpha(y: Float, solidEnd: Float, fadeEnd: Float): Float =
    ((fadeEnd - y) / (fadeEnd - solidEnd).coerceAtLeast(1f)).coerceIn(0f, 1f)

/** 与歌词/播放控件交界相同的互补模糊遮罩，再叠加白色渐隐；只处理滚动列表。 */
@Composable
internal fun Modifier.qqSearchTopFade(): Modifier {
    val surface = androidx.compose.material3.MaterialTheme.colorScheme.background
    val source = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val paint = remember { Paint() }
    return drawWithCache {
        val end = minOf(84.dp.toPx(), size.height)
        val solid = minOf(32.dp.toPx(), end)
        val whiteEnd = minOf(64.dp.toPx(), end)
        val region = Rect(0f, 0f, size.width, end)
        val clearMask = Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = solid, endY = end)
        val blurMask = Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = solid, endY = end)
        val whiteMask = Brush.verticalGradient(listOf(surface, Color.Transparent), startY = solid, endY = whiteEnd)
        val canBlur = !ExperiencePreferences.options.disableBlur && Build.VERSION.SDK_INT >= 31
        blurred.renderEffect = if (canBlur) BlurEffect(18.dp.toPx(), 18.dp.toPx(), TileMode.Clamp) else null
        var prepared = false
        onDrawWithContent {
            source.record { this@onDrawWithContent.drawContent() }
            if (canBlur && !prepared) {
                // 保留三倍模糊半径的采样边缘，列表滚动时只更新源图层。
                blurred.record(size = IntSize(size.width.toInt().coerceAtLeast(1),
                    minOf(size.height, end + 54.dp.toPx()).toInt().coerceAtLeast(1))) { drawLayer(source) }
                prepared = true
            }
            clipRect(top = end) { drawLayer(source) }
            drawIntoCanvas { canvas ->
                canvas.saveLayer(region, paint)
                clipRect(bottom = end) { drawLayer(source) }
                drawRect(clearMask, blendMode = BlendMode.DstIn)
                canvas.restore()
                if (canBlur) {
                    canvas.saveLayer(region, paint)
                    clipRect(bottom = end) { drawLayer(blurred) }
                    drawRect(blurMask, blendMode = BlendMode.DstIn)
                    canvas.restore()
                }
            }
            clipRect(bottom = end) { drawRect(whiteMask) }
        }
    }
}
