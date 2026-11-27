package com.musicone

import kotlin.math.abs
import kotlin.math.max

/** 为新切歌窗口分配槽位：快切浏览时顺次排在相邻槽位，普通切歌则复用尚未进场的屏外槽位。 */
internal fun nextLyricWindowSlot(slot: Float, offset: Float, direction: TrackTransitionDirection,
    browsing: Boolean = false): Float {
    val sign = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
    // 快速连切时保留中间页按顺序排列，一起滑向最新目标。
    if (browsing) return slot + sign
    return if ((slot + offset) * sign >= 1f) slot else slot + sign
}

/** 判断退场中的旧窗口是否还在屏幕内且位于本次切歌方向，避免队列循环到同名歌时反向滚回。 */
internal fun canReturnLyricWindow(slot: Float, currentSlot: Float, offset: Float,
    direction: TrackTransitionDirection): Boolean {
    val sign = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
    return abs(slot + offset) < 1f && (slot - currentSlot) * sign > 0f
}

/** 计算切歌窗口完全滑出屏幕所需的行程距离（含上下系统栏留白）。 */
internal fun lyricWindowTravelPx(top: Float, height: Float, screenHeight: Float): Float =
    max(height, max(top + height, screenHeight - top)).coerceAtLeast(1f)

/** 判断目标行是否已静止且精确停在阅读锚点（offset == 0 且无滚动和残余位移）。 */
internal fun lyricWindowAligned(targetOffset: Int?, scrolling: Boolean, seekOffset: Float): Boolean =
    targetOffset == 0 && !scrolling && abs(seekOffset) <= .5f
