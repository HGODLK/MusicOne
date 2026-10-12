package com.musicone.demo

import androidx.compose.ui.unit.dp

/** 焦点句下移时仍保持原有的顶部模糊过渡边界。 */
internal val PHONE_LYRICS_TOP_GLASS_LIMIT = 52.dp

/** 顶部玻璃使用展开后的小封面端点，屏外绘制缓冲只参与坐标转换。 */
internal data class PlayerLyricsTopGlass(val blurStart: Float, val clearStart: Float) {
    fun maskFraction(buffer: Float): Float =
        ((blurStart - buffer) / (clearStart - buffer).coerceAtLeast(1f)).coerceIn(0f, 1f)
}

internal fun phoneLyricsTopGlass(header: Float, extension: Float, compactCoverBottom: Float,
    limitAnchor: Float, focusGap: Float): PlayerLyricsTopGlass {
    val drawingTop = header - extension * 2f
    val start = compactCoverBottom - drawingTop
    val oldEnd = extension * 2f + playerLyricsTopFadeHeight(extension)
    val end = minOf(oldEnd, extension * 2f + limitAnchor - focusGap)
    return PlayerLyricsTopGlass(start, maxOf(start + 1f, end))
}
