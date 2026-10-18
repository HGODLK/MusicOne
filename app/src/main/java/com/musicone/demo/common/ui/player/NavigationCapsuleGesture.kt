package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

internal enum class CapsuleGestureDirection { PENDING, HORIZONTAL, UP, IGNORED }

internal fun capsuleGestureDirection(x: Float, y: Float, slop: Float): CapsuleGestureDirection =
    when (verticalSlopIntent(x, y, slop)) {
        -1 -> CapsuleGestureDirection.HORIZONTAL
        1 -> if (y < 0f) CapsuleGestureDirection.UP else CapsuleGestureDirection.IGNORED
        else -> CapsuleGestureDirection.PENDING
    }

internal fun capsuleShouldOpenQueue(distanceDp: Float, velocityYDp: Float,
    reverseDistanceDp: Float, completed: Boolean): Boolean = completed && playerGestureTarget(
        0f, -velocityYDp, distanceDp, original = false, completed = true,
        threshold = 1f, commitDistanceDp = 36f, reverseDistanceDp = reverseDistanceDp,
        flingDistanceDp = 16f, flingVelocityDp = 600f)

/** 同一轮输入只归属一个方向；上滑立即拖出队列，抬手按当前位置与速度收尾。 */
@Composable
internal fun Modifier.navigationCapsuleGesture(onContact: () -> Unit, blocked: () -> Boolean,
    onHorizontalDrag: (Float) -> Unit, onHorizontalEnd: () -> Unit, queueState: PlayerQueueState): Modifier {
    val contact = rememberUpdatedState(onContact)
    val dragBlocked = rememberUpdatedState(blocked)
    val horizontal = rememberUpdatedState(onHorizontalDrag)
    val horizontalEnd = rememberUpdatedState(onHorizontalEnd)
    return pointerInput(queueState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            contact.value()
            if (down.isConsumed || dragBlocked.value()) return@awaitEachGesture
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            val release = GestureReleaseVelocity(down.uptimeMillis, density, stablePlayerSettlement = true)
            val displacement = PlayerGestureDisplacement(original = false)
            var direction = CapsuleGestureDirection.PENDING
            var totalX = 0f
            var totalY = 0f
            var queueDragging = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.count { it.pressed } > 1 ||
                        (direction == CapsuleGestureDirection.PENDING && change.isConsumed)) break
                    change.historical.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val delta = change.position - change.previousPosition
                    val dy = if (!change.pressed) release.finalDelta(change.uptimeMillis, delta.y) else delta.y
                    release.moved(change.uptimeMillis, dy)
                    displacement.move(-dy)
                    totalX += delta.x
                    totalY += dy
                    val previous = direction
                    if (direction == CapsuleGestureDirection.PENDING) {
                        direction = capsuleGestureDirection(totalX, totalY, viewConfiguration.touchSlop)
                    }
                    when (direction) {
                        CapsuleGestureDirection.HORIZONTAL -> {
                            val amount = if (previous == CapsuleGestureDirection.PENDING) {
                                totalX - if (totalX > 0f) viewConfiguration.touchSlop else -viewConfiguration.touchSlop
                            } else delta.x
                            horizontal.value(amount)
                            change.consume()
                        }
                        CapsuleGestureDirection.UP -> {
                            val amount = if (previous == CapsuleGestureDirection.PENDING) {
                                if (!queueState.beginOpeningDrag()) break
                                queueDragging = true
                                totalY + viewConfiguration.touchSlop
                            } else dy
                            queueState.dragOpeningBy(amount)
                            change.consume()
                        }
                        CapsuleGestureDirection.IGNORED -> break
                        CapsuleGestureDirection.PENDING -> Unit
                    }
                    if (!change.pressed) {
                        if (queueDragging) {
                            val rawVelocity = tracker.calculateVelocity().y
                            val velocity = release.released(change.uptimeMillis, rawVelocity, true)
                            val open = !dragBlocked.value() && capsuleShouldOpenQueue(
                                displacement.distancePx / density, velocity / density,
                                displacement.reverseDistancePx / density, completed = true)
                            queueState.finishOpeningDrag(open,
                                release.settlingVelocity(change.uptimeMillis, rawVelocity, true),
                                gestureReleaseTimeNanos(change.uptimeMillis))
                            queueDragging = false
                        }
                        break
                    }
                }
            } finally {
                if (direction == CapsuleGestureDirection.HORIZONTAL) horizontalEnd.value()
                if (queueDragging) queueState.finishOpeningDrag(false, 0f)
            }
        }
    }
}
