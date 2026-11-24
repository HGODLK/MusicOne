package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

internal fun searchTopWhiteAlpha(y: Float, solidEnd: Float, fadeEnd: Float): Float =
    ((fadeEnd - y) / (fadeEnd - solidEnd).coerceAtLeast(1f)).coerceIn(0f, 1f)

/** 渐隐曲线（smootherstep）：两端斜率为零，与实色、与列表交界处都没有可见拐点。 */
internal fun searchTopFadeAlpha(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - x * x * x * (x * (x * 6f - 15f) + 10f)
}

private const val SearchTopFadeStopCount = 13

/**
 * 搜索结果顶部只叠页面背景色，不做模糊：40dp（分页文字下沿）内实色，之后按两端平缓的 S 曲线渐隐到 104dp。
 * 起步平缓使分页按钮底下仍近乎全遮，静止时首行（84dp 起）只剩极淡残余。
 */
@Composable
internal fun Modifier.mainSourceSearchTopFade(): Modifier {
    val surface = androidx.compose.material3.MaterialTheme.colorScheme.background
    return drawWithCache {
        val end = minOf(104.dp.toPx(), size.height)
        val solid = minOf(40.dp.toPx(), end)
        val last = (SearchTopFadeStopCount - 1).toFloat()
        val mask = Brush.verticalGradient(
            *Array(SearchTopFadeStopCount) { i -> i / last to surface.copy(alpha = surface.alpha * searchTopFadeAlpha(i / last)) },
            startY = solid, endY = end)
        onDrawWithContent {
            drawContent()
            drawRect(surface, size = size.copy(height = solid))
            drawRect(mask, topLeft = Offset(0f, solid), size = size.copy(height = end - solid))
        }
    }
}
