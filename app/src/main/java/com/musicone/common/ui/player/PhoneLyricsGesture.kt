package com.musicone

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

internal class PhoneLyricsTouchRegion {
    var closing = Rect.Zero
    var opening = Rect.Zero
}

/** 在不缩放的舞台坐标系识别歌词开合，避免小封面缩放放大手指位移。 */
@Composable
internal fun Modifier.phoneLyricsGesture(
    motion: PhoneLyricsMotion,
    region: PhoneLyricsTouchRegion,
    enabled: Boolean,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    positionOffset: () -> Offset = { Offset.Zero },
): Modifier {
    val latestVisible by rememberUpdatedState(visible)
    val latestChange by rememberUpdatedState(onVisibleChange)
    val latestOffset by rememberUpdatedState(positionOffset)
    val presentation = LocalPhonePlayerPresentation.current
    val turn = LocalPhonePlayerTurn.current
    val canTopPull = rememberUpdatedState {
        motion.lyricsProgress >= 1f && motion.listEdge.atTop() &&
            presentation?.menuLocks?.active != true && turn?.orientationChanging != true
    }
    return pointerInput(motion, region, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val original = latestVisible
            val startRegion = if (original) region.closing else region.opening
            val change = { target: Boolean -> latestChange(target) }
            val point = down.position - latestOffset()
            if (startRegion.contains(point)) {
                awaitLyricsDrag(down, motion, original, null, change)
            } else if (original && region.opening.contains(point)) {
                // opening 是控件上方的区域，这里只剩歌词列表。手指先交给列表滚动，到顶后才接成关闭；中途让出后还能再次接管。
                while (currentEvent.changes.any { it.id == down.id && it.pressed }) {
                    val start = awaitLyricsTopPullStart(down.id) { canTopPull.value() } ?: break
                    if (awaitLyricsDrag(start, motion, true, canTopPull.value, change)) break
                }
            }
        }
    }
}

/** 返回 true 表示这根手指已被开合手势接管并完成收尾。 */
private suspend fun AwaitPointerEventScope.awaitLyricsDrag(
    start: PointerInputChange,
    motion: PhoneLyricsMotion,
    original: Boolean,
    canTopPull: (() -> Boolean)?,
    onVisibleChange: (Boolean) -> Unit,
): Boolean {
    val tracker = VelocityTracker().apply { addPosition(start.uptimeMillis, start.position) }
    val release = GestureReleaseVelocity(start.uptimeMillis, density, stablePlayerSettlement = true)
    val displacement = PlayerGestureDisplacement(original)
    var started = false
    var distance = 0f
    var initial = motion.lyricsProgress
    val travel = motion.dragTravelPx.coerceAtLeast(1f)
    fun move(delta: Float) {
        if (canTopPull != null) {
            val progress = motion.lyricsProgress
            val split = lyricsTopPullSplit(delta, progress, travel, motion.listEdge.atTop())
            if (split.progress != 0f) motion.dragTo(progress + split.progress)
            if (split.list != 0f) motion.listEdge.scrollList(split.list)
        } else {
            distance -= delta
            motion.dragTo(initial + distance / travel)
        }
    }
    try {
        val end = awaitOwnedVerticalGesture(start, tracker, release,
            begin = { over ->
                val allowed = if (canTopPull != null) over > 0f && canTopPull() else
                    (over < 0f && motion.lyricsProgress < 1f) || (over > 0f && motion.lyricsProgress > 0f)
                if (allowed) {
                    initial = motion.lyricsProgress
                    motion.beginDrag()
                    started = true
                }
                allowed
            }, move = ::move, observe = { displacement.move(-it) })
        if (end == null || !started) return false
        val completed = end.completed
        val rawVelocityY = tracker.calculateVelocity().y
        val now = end.timeMillis
        // 判定速度与动画接续速度分别计算，抬手微抖不改变原方向。
        val intentVelocityY = release.released(now, rawVelocityY, completed)
        val target = lyricsDragTarget(motion.lyricsProgress, intentVelocityY / density,
            -displacement.distancePx / density, original, completed,
            displacement.reverseDistancePx / density)
        val settlingVelocity = release.settlingVelocity(now, rawVelocityY, completed)
        motion.settleDrag(target, settlingVelocity, gestureReleaseTimeNanos(now))
        started = false
        onVisibleChange(target)
        return true
    } finally {
        if (started) {
            val target = lyricsDragTarget(motion.lyricsProgress, 0f, original = original,
                completed = false)
            motion.settleDrag(target, 0f)
            onVisibleChange(target)
        }
    }
}

internal fun lyricsDragTarget(progress: Float, velocityY: Float, distanceDp: Float = -24f,
    original: Boolean = false, completed: Boolean = true, reverseDistanceDp: Float = 0f): Boolean =
    playerGestureTarget(progress, -velocityY, -distanceDp, original, completed,
        threshold = .5f, reverseDistanceDp = reverseDistanceDp,
        flingDistanceDp = 24f, flingVelocityDp = 500f)
