package com.musicone

import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import kotlin.math.ceil
import kotlin.math.floor

/** 模糊要覆盖的纵向范围；清晰与模糊的过渡由外层遮罩负责。 */
internal data class LyricBlurBand(val top: Float, val bottom: Float)

internal fun lyricBottomBlurBand(start: Float, height: Float): LyricBlurBand? =
    if (start >= height) null else LyricBlurBand(start, height)

internal fun lyricTopBlurBand(top: Float, end: Float): LyricBlurBand? =
    if (end <= top) null else LyricBlurBand(top, end)

/** 只采样可见带及卷积留白；80px 缓冲块固定在原图网格上，控件运动不使纹理逐像素抖动。 */
internal fun lyricBlurSampleBounds(top: Float, bottom: Float, height: Float, radius: Float): Pair<Float, Float> {
    val padding = radius * 3f + 8f
    return (floor((top - padding) / 80f) * 80f).coerceIn(0f, height) to
        (ceil((bottom + padding) / 80f) * 80f).coerceIn(0f, height)
}

/** 容量要放得下可见带、两侧留白和对齐网格的余量。 */
internal fun lyricBlurSampleFits(visibleHeight: Float, capacity: Float, radius: Float): Boolean =
    capacity >= visibleHeight + (radius * 3f + 8f) * 2f + 80f

/** 原始分辨率模糊。容量从小到大排列，平时只用局部纹理；没有绘制到的图层不占纹理。 */
internal class LyricBackdropBlurScene(
    cache: CacheDrawScope,
    source: GraphicsLayer,
    private val radius: Float,
    maxBandHeights: List<Float>,
) {
    private val height = cache.size.height
    private class Sample(val content: GraphicsLayer, val blurred: GraphicsLayer, val capacity: Float)
    private val samples = run {
        val density = Density(cache.density, cache.fontScale)
        val direction = cache.layoutDirection
        val width = cache.size.width
        maxBandHeights.map { lyricBlurSampleCapacity(it, height, radius) }.distinct().sorted().map { capacity ->
            val content = cache.obtainGraphicsLayer().apply {
                record(density, direction, lyricBlurSampleSize(width, height)) { drawLayer(source) }
            }
            val blurred = cache.obtainGraphicsLayer().apply {
                renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                record(density, direction, lyricBlurSampleSize(width, capacity)) { drawLayer(content) }
            }
            Sample(content, blurred, capacity)
        }
    }

    fun draw(scope: DrawScope, band: LyricBlurBand?) = with(scope) {
        if (band == null) return@with
        val visibleTop = band.top.coerceIn(0f, height)
        val visibleBottom = band.bottom.coerceIn(visibleTop, height)
        if (visibleBottom <= visibleTop) return@with
        val sample = samples.firstOrNull { lyricBlurSampleFits(visibleBottom - visibleTop, it.capacity, radius) }
            ?: samples.last()
        val requestedTop = lyricBlurSampleBounds(visibleTop, visibleBottom, height, radius).first
        val top = lyricBlurSampleOrigin(requestedTop, sample.capacity, height)
        // 移动采样窗口只更新矩阵，模糊层尺寸和录制指令保持不变。
        sample.content.translationY = -top
        sample.blurred.translationY = top
        clipRect(top = visibleTop, bottom = visibleBottom) { drawLayer(sample.blurred) }
    }
}
