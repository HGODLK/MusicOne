package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

internal fun shouldOpenPlayer(progress: Float, velocityY: Float, releasedNormally: Boolean,
                              distanceDp: Float = -24f, reverseDistanceDp: Float = 0f): Boolean =
    playerGestureTarget(progress, -velocityY, -distanceDp, false, releasedNormally,
        threshold = .5f, reverseDistanceDp = reverseDistanceDp,
        flingDistanceDp = 32f, flingVelocityDp = 650f)

@Composable
internal fun Modifier.miniPlayerOpenGesture(
    motion: PageMotion,
    onStart: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
): Modifier {
    val startCallback = rememberUpdatedState(onStart)
    val changeCallback = rememberUpdatedState(onExpandedChange)
    return pointerInput(motion) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (motion.mounted) return@awaitEachGesture
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            val release = GestureReleaseVelocity(down.uptimeMillis, density, stablePlayerSettlement = true)
            val displacement = PlayerGestureDisplacement(false)
            var dragging = false
            var travel = 1f
            var distance = 0f
            fun move(deltaY: Float) {
                distance = (distance - deltaY).coerceIn(0f, travel)
                motion.dragTo(distance / travel)
            }
            try {
                // 与播放页、歌词共用完整事件路径，末帧位移和正常抬手不会被子级消费丢掉。
                val end = awaitOwnedVerticalGesture(down, tracker, release,
                    begin = { over ->
                        (over < 0f && motion.beginOpeningDrag()).also { accepted ->
                            if (accepted) {
                                dragging = true
                                travel = ((motion.sources["surface"]?.bounds?.top ?: motion.hostBounds.bottom) - motion.hostBounds.top).coerceAtLeast(size.height.toFloat())
                                startCallback.value()
                            }
                        }
                    }, move = ::move, observe = { displacement.move(-it) })
                if (end != null && dragging) {
                    val completed = end.completed
                    val now = end.timeMillis
                    val rawVelocity = tracker.calculateVelocity().y
                    val velocity = release.released(now, rawVelocity, completed)
                    val open = shouldOpenPlayer(motion.dragProgress, velocity / density, completed,
                        -displacement.distancePx / density, displacement.reverseDistancePx / density)
                    motion.releaseDragWithVelocity(-release.settlingVelocity(now, rawVelocity, completed) / travel,
                        maxVelocity = 6f, releaseTimeNanos = gestureReleaseTimeNanos(now))
                    motion.request(open, rebound = !open)
                    changeCallback.value(open)
                    dragging = false
                }
            } finally {
                if (dragging) {
                    val open = shouldOpenPlayer(motion.dragProgress, 0f, false)
                    motion.releaseDragWithVelocity(0f)
                    motion.request(open, rebound = !open)
                    changeCallback.value(open)
                }
            }
        }
    }
}
