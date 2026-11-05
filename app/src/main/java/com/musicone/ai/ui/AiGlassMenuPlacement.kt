package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize

/**
 * AI 玻璃菜单的锚定位置：优先贴在触发按钮下方，下方放不下就翻到按钮上方。
 * 按钮比菜单窄时右对齐按钮右边缘，比菜单宽（整行按钮）时按按钮中心居中，最后收进安全边距。
 */
internal fun aiGlassMenuOffset(anchor: Rect, host: Rect, size: IntSize, gap: Float, margin: Float): Offset {
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val minX = host.left + margin
    val maxX = (host.right - width - margin).coerceAtLeast(minX)
    val minY = host.top + margin
    val maxY = (host.bottom - height - margin).coerceAtLeast(minY)
    val preferredX = if (anchor.width >= width) anchor.center.x - width / 2f else anchor.right - width
    val below = anchor.bottom + gap
    val preferredY = if (below + height <= host.bottom - margin) below else anchor.top - gap - height
    return Offset(preferredX.coerceIn(minX, maxX), preferredY.coerceIn(minY, maxY))
}
