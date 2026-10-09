package com.musicone.demo

import kotlin.math.roundToInt

/** 歌词视口的关闭端点必须完全越过舞台底边，展开端点保留顶部延伸并计入绘制缓冲补偿。 */
internal fun phoneLyricsWindowTravel(height: Int, header: Int, topExtension: Int, topBuffer: Int = 0): Float =
    (height - header + topExtension + topBuffer).toFloat().coerceAtLeast(1f)

internal fun phoneLyricsWindowTop(
    height: Int,
    header: Int,
    topExtension: Int,
    progress: Float,
    prewarming: Boolean,
    topBuffer: Int = 0,
    windowOffsetPx: Float? = null,
): Int = if (prewarming) {
    header - topExtension
} else if (windowOffsetPx != null) {
    header - topExtension + windowOffsetPx.roundToInt()
} else {
    motionLerp((height + topBuffer).toFloat(), (header - topExtension).toFloat(), progress.coerceIn(0f, 1f))
        .roundToInt()
}
