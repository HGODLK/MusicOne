package com.musicone.demo

import kotlin.math.abs
import kotlin.math.max

/** 普通交接合并屏外待播页；快切流动保留相邻歌词页并共同追踪最新位置。 */
internal fun nextLyricWindowSlot(slot: Float, offset: Float, direction: TrackTransitionDirection,
    browsing: Boolean = false): Float {
    val sign = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
    // 连续流动保留已取得歌词的中间页，共同追踪最终位置，不逐页排队播放完整入场动画。
    if (browsing) return slot + sign
    return if ((slot + offset) * sign >= 1f) slot else slot + sign
}

/** 只有位于本次切换方向的可见旧页可以接回，队列绕回同名歌曲不能反向滚回旧位置。 */
internal fun canReturnLyricWindow(slot: Float, currentSlot: Float, offset: Float,
    direction: TrackTransitionDirection): Boolean {
    val sign = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
    return abs(slot + offset) < 1f && (slot - currentSlot) * sign > 0f
}

/** 以窗口真实上下边缘计算距离，包含状态栏、导航栏及歌词栏之外的留白。 */
internal fun lyricWindowTravelPx(top: Float, height: Float, screenHeight: Float): Float =
    max(height, max(top + height, screenHeight - top)).coerceAtLeast(1f)

/** 首帧不仅要出现目标行，还必须在阅读锚点上且没有另一段列表/seek 位移。 */
internal fun lyricWindowAligned(targetOffset: Int?, scrolling: Boolean, seekOffset: Float): Boolean =
    targetOffset == 0 && !scrolling && abs(seekOffset) <= .5f
