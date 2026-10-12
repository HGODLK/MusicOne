package com.musicone.demo

import androidx.compose.ui.unit.Density
import kotlin.math.max
import kotlin.math.min

/** 坐标均相对于扩展后的歌词绘制视口，和顶部玻璃、底部控件使用同一原点。 */
internal data class LyricReadingBounds(val topClearPx: Float, val bottomInsetPx: Float)

/** 将焦点句的视觉中心放在清晰区域三分之一处，并为整句保留边界。 */
internal fun adaptiveLyricReadingAnchor(
    viewportHeight: Float,
    readableTop: Float,
    readableBottom: Float,
    rowHeight: Float,
    gap: Float,
): Float {
    val top = (readableTop + gap).coerceAtLeast(0f)
    val bottom = min(viewportHeight, readableBottom - gap).coerceAtLeast(top)
    val height = rowHeight.coerceAtLeast(1f)
    val desired = top + (bottom - top) / 3f - height / 2f
    return desired.coerceIn(top, max(top, bottom - height))
}

internal fun lyricReadingAnchorDp(
    viewportHeightDp: Float,
    bounds: LyricReadingBounds?,
    density: Density,
    rowHeightDp: Float,
): Float {
    if (bounds == null) return adaptiveLyricReadingAnchor(
        viewportHeightDp, 24f, viewportHeightDp - 24f, rowHeightDp, 8f)
    return adaptiveLyricReadingAnchor(
        viewportHeightDp,
        bounds.topClearPx / density.density,
        viewportHeightDp - bounds.bottomInsetPx / density.density,
        rowHeightDp,
        8f,
    )
}
