package com.musicone.demo

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal data class QueueSongMenuRegion(val bounds: Rect, val above: Boolean)

/** 播放队列的歌曲占满整行，菜单使用上方或下方较宽裕的一侧，整行都作为避让区域。 */
internal fun queueSongMenuRegion(width: Float, height: Float, anchor: Rect, margin: Float, gap: Float): QueueSongMenuRegion {
    val top = margin.coerceAtMost(height / 2f)
    val bottom = (height - margin).coerceAtLeast(top)
    val aboveEnd = (anchor.top - gap).coerceIn(top, bottom)
    val belowStart = (anchor.bottom + gap).coerceIn(top, bottom)
    val above = aboveEnd - top >= bottom - belowStart
    val left = margin.coerceAtMost(width / 2f)
    return QueueSongMenuRegion(
        Rect(left, if (above) top else belowStart, (width - margin).coerceAtLeast(left), if (above) aboveEnd else bottom),
        above,
    )
}

/** 在同一次测量中取得菜单真实高度并定位，避免首帧猜高度和展开子菜单时盖住歌曲。 */
internal fun Modifier.queueSongMenuPlacement(anchor: Rect, host: Rect): Modifier = layout { measurable, constraints ->
    val localAnchor = anchor.translate(-host.left, -host.top)
    val region = queueSongMenuRegion(
        constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), localAnchor, 12.dp.toPx(), 8.dp.toPx(),
    )
    val menu = measurable.measure(Constraints(
        maxWidth = region.bounds.width.roundToInt().coerceAtLeast(0),
        maxHeight = region.bounds.height.roundToInt().coerceAtLeast(0),
    ))
    val x = (localAnchor.right - menu.width).coerceIn(region.bounds.left, (region.bounds.right - menu.width).coerceAtLeast(region.bounds.left))
    val y = if (region.above) region.bounds.bottom - menu.height else region.bounds.top
    layout(constraints.maxWidth, constraints.maxHeight) { menu.place(x.roundToInt(), y.roundToInt()) }
}
