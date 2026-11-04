package com.musicone

import androidx.compose.foundation.lazy.LazyListLayoutInfo
import kotlin.math.abs

/** 长段落把下一句挤出视口时，以已测量邻行推进行程，不改走快速定位或换窗。 */
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
                // 估计行程结束后仍由同一驱动接续实测余量，不瞬移补齐。
            }
        }
        if (entrance != null) entrance.scrollPlayback(move)
        else list.scroll { move { scrollBy(it) } }
    } finally { if (!stillFollowing()) pane.playbackStep.reset() }
}

/** 自然单句由原逐行驱动持有滚动权；其他事务接管时保留画面并转换坐标。 */
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
            // 下一自然句保留运动速度；可见接管只转换坐标，不重置焦点强调。
            if (!stillFollowing()) motion.reset()
        }
    }
}
