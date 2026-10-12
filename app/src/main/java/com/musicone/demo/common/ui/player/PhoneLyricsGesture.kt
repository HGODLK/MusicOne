package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
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
): Modifier {
    val latestVisible by rememberUpdatedState(visible)
    val latestChange by rememberUpdatedState(onVisibleChange)
    return pointerInput(motion, region, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val original = latestVisible
            val startRegion = if (original) region.closing else region.opening
            if (!startRegion.contains(down.position)) return@awaitEachGesture
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            val release = GestureReleaseVelocity(down.uptimeMillis, density, stablePlayerSettlement = true)
            val displacement = PlayerGestureDisplacement(original)
            var started = false
            var distance = 0f
            var initial = motion.lyricsProgress
            val travel = motion.dragTravelPx.coerceAtLeast(1f)
            fun move(delta: Float) {
                distance -= delta
                motion.dragTo(initial + distance / travel)
            }
            try {
                val end = awaitOwnedVerticalGesture(down, tracker, release,
                    begin = { over ->
                        val allowed = (over < 0f && motion.lyricsProgress < 1f) ||
                            (over > 0f && motion.lyricsProgress > 0f)
                        if (allowed) {
                            initial = motion.lyricsProgress
                            motion.beginDrag()
                            started = true
                        }
                        allowed
                    }, move = ::move, observe = { displacement.move(-it) })
                if (end != null && started) {
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
                    latestChange(target)
                }
            } finally {
                if (started) {
                    val target = lyricsDragTarget(motion.lyricsProgress, 0f, original = original,
                        completed = false)
                    motion.settleDrag(target, 0f)
                    latestChange(target)
                }
            }
        }
    }
}

internal fun lyricsDragTarget(progress: Float, velocityY: Float, distanceDp: Float = -24f,
    original: Boolean = false, completed: Boolean = true, reverseDistanceDp: Float = 0f): Boolean =
    playerGestureTarget(progress, -velocityY, -distanceDp, original, completed,
        threshold = .5f, reverseDistanceDp = reverseDistanceDp,
        flingDistanceDp = 24f, flingVelocityDp = 500f)
