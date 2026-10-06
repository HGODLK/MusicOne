package com.musicone.demo

internal enum class LyricClickNavigation { NONE, SCROLL, LAYERED }

/** 屏内目标始终从实际视口连续滚动；仅跨屏目标使用缓存图层交接。 */
internal fun lyricClickNavigation(
    targetVisible: Boolean,
): LyricClickNavigation = when {
    !targetVisible -> LyricClickNavigation.LAYERED
    else -> LyricClickNavigation.SCROLL
}

/** 列表的阅读锚点已由顶部 padding 提供；item.offset 为零即是最终位置。 */
internal fun lyricVisibleSeekTravel(targetOffset: Int): Float = targetOffset.toFloat()
