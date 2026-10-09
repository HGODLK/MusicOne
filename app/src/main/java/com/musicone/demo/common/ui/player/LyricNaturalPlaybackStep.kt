package com.musicone.demo

/** 自然单句由原逐行驱动持有滚动权；其他事务接管时保留画面并转换坐标。 */
internal suspend fun runLyricNaturalPlaybackStep(
    motion: LyricPlaybackStepMotion,
    current: Int,
    visibleIndices: List<Int>,
    travel: Float,
    stillFollowing: () -> Boolean,
    scroll: suspend (suspend ((Float) -> Float) -> Unit) -> Unit,
) {
    scroll { scrollBy ->
        try {
            motion.move(current, visibleIndices, travel, scrollBy)
        } finally {
            // 下一自然句保留运动速度；可见接管只转换坐标，不重置焦点强调。
            if (!stillFollowing()) motion.reset()
        }
    }
}
