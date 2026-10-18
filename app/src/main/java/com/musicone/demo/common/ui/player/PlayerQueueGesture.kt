package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

internal fun queueListCanStartDismiss(atTop: Boolean, scrolling: Boolean,
    overscrolling: Boolean, phase: MotionPhase): Boolean =
    atTop && !scrolling && !overscrolling && phase == MotionPhase.SHOWN

internal fun queueGestureStaysOpen(progress: Float, distanceDp: Float, velocityYDp: Float,
    reverseDistanceDp: Float, completed: Boolean): Boolean = !completed || playerGestureTarget(
        progress, -velocityYDp, -distanceDp, original = true, completed = true,
        threshold = .7f, commitDistanceDp = 64f, reverseDistanceDp = reverseDistanceDp,
        flingDistanceDp = 24f, flingVelocityDp = 650f)

/** 起手锁定归属，只按本轮手指位移收起；惯性、回弹和取消不会提交关闭。 */
@Composable
internal fun Modifier.queueDismissGesture(state: PlayerQueueState, canStart: () -> Boolean): Modifier {
    val currentCanStart = rememberUpdatedState(canStart)
    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!currentCanStart.value()) return@awaitEachGesture
            val motion = state.motion
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            val release = GestureReleaseVelocity(down.uptimeMillis, density, stablePlayerSettlement = true)
            val displacement = PlayerGestureDisplacement(original = true)
            var dragging = false
            val travel = state.sheetHeightPx.coerceAtLeast(1f)
            try {
                val end = awaitOwnedVerticalGesture(down, tracker, release,
                    begin = { over ->
                        (over > 0f && motion.wantsOpen && motion.beginDrag()).also { dragging = it }
                    },
                    move = { delta -> motion.dragTo(motion.dragProgress - delta / travel) },
                    observe = { displacement.move(-it) })
                if (end != null && dragging) {
                    val rawVelocity = tracker.calculateVelocity().y
                    val velocity = release.released(end.timeMillis, rawVelocity, end.completed)
                    val open = queueGestureStaysOpen(motion.dragProgress,
                        -displacement.distancePx / density, velocity / density,
                        displacement.reverseDistancePx / density, end.completed)
                    val settlingVelocity = -release.settlingVelocity(end.timeMillis, rawVelocity, end.completed) / travel
                    motion.releaseDragWithVelocity(playerGestureSettlingVelocity(open, settlingVelocity),
                        maxVelocity = 6f, releaseTimeNanos = gestureReleaseTimeNanos(end.timeMillis))
                    motion.request(open, rebound = open)
                    dragging = false
                }
            } finally {
                if (dragging) {
                    motion.releaseDragWithVelocity(0f)
                    motion.request(true, rebound = true)
                }
            }
        }
    }
}
