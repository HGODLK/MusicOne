package com.musicone

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * 歌曲菜单锚点锁定状态。
 *
 * 用于在菜单尺寸变化（例如进入编辑模式导致宽高改变）时保持菜单位置稳定，
 * 避免因重新测量导致菜单跳动。
 */
internal class SongMenuAnchorLock {
    /** 当前锁定的会话标识，用于区分不同锚点对应的菜单实例。 */
    var session: Long = -1L
    /** 锁定的菜单垂直中心位置，为空表示尚未锁定。 */
    var centerY: Float? = null
}

/**
 * 计算菜单首次弹出时的顶部位置：菜单放置在锚点上方并留出间距。
 *
 * 结果会被限制在 [topLimit] 与可用的最大顶部位置之间，防止菜单超出边界。
 */
internal fun songMenuInitialTop(
    anchorTop: Float,
    menuHeight: Float,
    topLimit: Float,
    bottomLimit: Float,
    gap: Float,
): Float {
    // 菜单底部不得超过 bottomLimit，因此最大顶部位置由可用高度决定
    val maxTop = (bottomLimit - menuHeight).coerceAtLeast(topLimit)
    return (anchorTop - menuHeight - gap).coerceIn(topLimit, maxTop)
}

/**
 * 根据给定垂直中心计算菜单顶部位置，并限制在上下边界之间。
 */
internal fun songMenuCenteredTop(
    centerY: Float,
    menuHeight: Float,
    topLimit: Float,
    bottomLimit: Float,
): Float {
    // 菜单底部不得超过 bottomLimit，因此最大顶部位置由可用高度决定
    val maxTop = (bottomLimit - menuHeight).coerceAtLeast(topLimit)
    return (centerY - menuHeight / 2f).coerceIn(topLimit, maxTop)
}

/**
 * 计算菜单的水平位置。
 *
 * 编辑模式下居中显示；普通模式下右对齐锚点并限制在左右边界内。
 */
internal fun songMenuHorizontalX(
    anchorRight: Float,
    menuWidth: Float,
    leftLimit: Float,
    rightLimit: Float,
    centered: Boolean,
): Float {
    if (centered) {
        // 在可用水平区间内居中
        return leftLimit + ((rightLimit - leftLimit - menuWidth).coerceAtLeast(0f)) / 2f
    }
    // 菜单右侧对齐锚点右侧，且不得超出左右边界
    val maxLeft = (rightLimit - menuWidth).coerceAtLeast(leftLimit)
    return (anchorRight - menuWidth).coerceIn(leftLimit, maxLeft)
}

/**
 * 为歌曲菜单提供基于锚点矩形的自定义布局：负责水平定位与垂直定位。
 *
 * @param session 当前锚点会话标识，变化时重置锁定状态
 * @param anchor 锚点矩形（触发菜单的控件位置）
 * @param leftLimit 水平左边界
 * @param rightLimit 水平右边界
 * @param topLimit 普通模式下的垂直上边界
 * @param bottomLimit 垂直下边界
 * @param editingTopLimit 编辑模式下的垂直上边界
 * @param gap 菜单与锚点之间的间距
 * @param editing 是否处于编辑模式
 * @param lock 锚点锁定状态，用于保持菜单垂直位置稳定
 */
internal fun Modifier.songMenuAnchoredPlacement(
    session: Long,
    anchor: Rect,
    leftLimit: Float,
    rightLimit: Float,
    topLimit: Float,
    bottomLimit: Float,
    editingTopLimit: Float,
    gap: Float,
    editing: Boolean,
    lock: SongMenuAnchorLock,
): Modifier = layout { measurable, constraints ->
    // 以测量获得菜单实际宽高
    val menu = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    // 会话变化时清空已锁定的中心位置
    if (lock.session != session) {
        lock.session = session
        lock.centerY = null
    }
    // 非编辑模式下首次获得有效高度时，锁定初始中心位置
    if (!editing && lock.centerY == null && menu.height > 0) {
        val initialTop = songMenuInitialTop(
            anchor.top,
            menu.height.toFloat(),
            topLimit,
            bottomLimit,
            gap,
        )
        lock.centerY = initialTop + menu.height / 2f
    }
    val x = songMenuHorizontalX(
        anchor.right,
        menu.width.toFloat(),
        leftLimit,
        rightLimit,
        centered = editing,
    )
    val y = if (editing) {
        // 编辑模式下垂直居中于可用区域
        songMenuCenteredTop(
            (editingTopLimit + bottomLimit) / 2f,
            menu.height.toFloat(),
            editingTopLimit,
            bottomLimit,
        )
    } else {
        // 普通模式下使用锁定的中心位置，缺失时回退到初始位置
        val center = lock.centerY ?: (
            songMenuInitialTop(
                anchor.top,
                menu.height.toFloat(),
                topLimit,
                bottomLimit,
                gap,
            ) + menu.height / 2f
            )
        songMenuCenteredTop(center, menu.height.toFloat(), topLimit, bottomLimit)
    }
    layout(menu.width, menu.height) {
        menu.place(x.roundToInt(), y.roundToInt())
    }
}