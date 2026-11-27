package com.musicone

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlin.math.abs

/** 计算滚动到目标行的距离：目标行已在屏内直接取其 offset，被长句挤出屏外时按邻行高度估算。 */
internal fun lyricNaturalStepTravel(layout: LazyListLayoutInfo, target: Int): Float? {
    val item = layout.visibleItemsInfo.firstOrNull { it.index == target }
    if (item != null) return item.offset.toFloat()
    val nearest = layout.visibleItemsInfo.minByOrNull { abs(it.index - target) } ?: return null
    return (nearest.offset + (target - nearest.index) * (nearest.size + layout.mainAxisItemSpacing)).toFloat()
}

internal suspend fun runLyricNaturalListStep(pane: LyricWindowPane, current: Int,
    entrance: LyricEntrancePlaybackScroll? = null, stillFollowing: () -> Boolean) {
    val list = pane.listState
    val lease = pane.listHost.scrollLease()
    val canScroll = { lease() && (entrance == null || entrance.canScroll()) }
    try {
        val move: suspend ((Float) -> Float) -> Unit = { scrollBy ->
            while (stillFollowing() && canScroll()) {
                val layout = list.layoutInfo
                val offset = lyricNaturalStepTravel(layout, current) ?: break
                val travel = entrance?.playbackTravel(offset) ?: offset
                var consumed = 0f
                if (!pane.playbackStep.move(current, layout.visibleItemsInfo.map { it.index }, travel,
                        { scrollBy(it).also { delta -> consumed += delta } }, canScroll)) break
                val actual = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
                val remaining = actual?.let { entrance?.playbackTravel(it.offset.toFloat()) ?: it.offset.toFloat() }
                val boundary = remaining != null && (remaining > 0 && !list.canScrollForward ||
                    remaining < 0 && !list.canScrollBackward)
                if (remaining != null && (abs(remaining) <= 1 || boundary) || abs(consumed) < .5f) {
                    pane.playbackStep.completeStep(current)
                    break
                }
                // 按估算距离滚完后，如果还没完全对齐，继续循环用实测偏移平滑补齐。
            }
        }
        if (entrance != null) entrance.scrollPlayback(move)
        else list.scroll { move { scrollBy(it) } }
    } finally { if (!stillFollowing()) pane.playbackStep.reset() }
}

/** 执行自然播放滚动，被打断交接时重置坐标系但保留当前画面。 */
internal suspend fun runLyricNaturalPlaybackStep(
    motion: LyricPlaybackStepMotion,
    current: Int,
    visibleIndices: List<Int>,
    travel: Float,
    stillFollowing: () -> Boolean,
    canScroll: () -> Boolean = { true },
    scroll: suspend (suspend ((Float) -> Float) -> Unit) -> Unit,
) {
    scroll { scrollBy ->
        try {
            if (motion.move(current, visibleIndices, travel, scrollBy, canScroll)) motion.completeStep(current)
        } finally {
            // 不再继续跟随时释放滚动状态，转换剩余行位移坐标。
            if (!stillFollowing()) motion.reset()
        }
    }
}
