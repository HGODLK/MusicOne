package com.musicone

import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt

/** 同尺寸正反横屏也必须确认新方向端点，不能只凭旧列表已有尺寸开始定位。 */
internal fun lyricTurnViewportReady(expected: LyricPlacementGeometry, actual: LyricPlacementGeometry,
    rotation: Int, targetRotation: Int, hostReady: Boolean, endpointReady: Boolean): Boolean =
    expected.measured && actual == expected && rotation == targetRotation && hostReady && endpointReady

/** 已测量的段落直接到最终偏移，只有未测量的跨行手动阅读才需要先取得新行高。 */
internal suspend fun placeLyricTurnReadingPosition(
    position: LyricReadingPosition,
    geometry: LyricPlacementGeometry,
    current: () -> Boolean,
    rowHeight: () -> Int?,
    scrollTo: suspend (Int, Int) -> Unit,
    nextFrame: suspend () -> Unit = { withFrameNanos { } },
    rowMotionOffset: () -> Float = { 0f },
): Boolean {
    if (!current()) return false
    var measuredHeight = rowHeight()
    if (!position.following && position.offset < 0f && position.rowHeight > 0 && measuredHeight == null) {
        scrollTo(position.line, 0)
        nextFrame()
        if (!current()) return false
        measuredHeight = rowHeight() ?: return false
    }
    if (!current()) return false
    val offset = (position.targetOffset(geometry.height, measuredHeight ?: 0) - rowMotionOffset()).roundToInt()
    scrollTo(position.line, -offset)
    nextFrame()
    return current()
}
