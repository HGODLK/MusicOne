package com.musicone.demo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize

/** 使用本轮实际测量尺寸定位，首帧就避开播放控件和系统安全区。 */
internal fun settingsMenuOffset(origin: Offset, safe: Rect, size: IntSize): Offset {
    val maxX = (safe.right - size.width).coerceAtLeast(safe.left)
    val maxY = (safe.bottom - size.height).coerceAtLeast(safe.top)
    val y = if (origin.y + size.height <= safe.bottom) origin.y else origin.y - size.height
    return Offset((origin.x - size.width / 2f).coerceIn(safe.left, maxX), y.coerceIn(safe.top, maxY))
}
