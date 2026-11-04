package com.musicone

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/** 横屏观看方向的下划交回原 PageMotion，歌词和封面横向切歌仍由各自识别。 */
@Composable
internal fun Modifier.phoneLandscapePlayerDismiss(motion: PageMotion, flight: PhonePlayerFlightRotation,
    enabled: Boolean, turn: PhonePlayerTurn? = null, coverBounds: () -> Rect, viewingAngle: () -> Float = { 0f }, canStartDrag: () -> Boolean = { true },
    onDragStarted: () -> Unit = {}, onDismiss: () -> Unit): Modifier {
    val rotation = LocalPhonePlayerRotation.current
    val latestRotation = rememberUpdatedState(rotation)
    val latestAngle = rememberUpdatedState(viewingAngle)
    val latestBounds = rememberUpdatedState(coverBounds)
    val latestCanStart = rememberUpdatedState(canStartDrag)
    val latestDragStarted = rememberUpdatedState(onDragStarted)
    val latestDismiss = rememberUpdatedState(onDismiss)
    val latestEnabled = rememberUpdatedState(enabled)
    return pointerInput(motion, flight) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!latestEnabled.value ||
                !latestBounds.value().contains(down.position + motion.hostBounds.topLeft)) return@awaitEachGesture
            val activeRotation = latestRotation.value
            val angle = latestAngle.value()
            val direction = phonePlayerFlightRotate(Offset(0f, 1f), Offset.Zero, angle)
            fun projected(position: Offset) = position.x * direction.x + position.y * direction.y
            val tracker = VelocityTracker()
            val release = GestureReleaseVelocity(down.uptimeMillis, density, stablePlayerSettlement = true)
            val displacement = PlayerGestureDisplacement(false)
            tracker.addPosition(down.uptimeMillis, Offset(0f, projected(down.position)))
            var totalX = 0f
            var totalY = 0f
            var owned = false
            var dragging = false
            var distance = 0f
            var startingProgress = motion.value
            val travel = (abs(direction.x) * size.width + abs(direction.y) * size.height).coerceAtLeast(1f)
            flight.updateTravel(maxOf(size.width, size.height).toFloat())
            var normalRelease = false
            var releaseTime = down.uptimeMillis
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.count { it.pressed } > 1 || (!owned && change.isConsumed)) break
                    releaseTime = change.uptimeMillis
                    val delta = change.position - change.previousPosition
                    val amount = projected(delta)
                    totalX += amount
                    totalY += delta.x * direction.y - delta.y * direction.x
                    displacement.move(amount)
                    tracker.addPosition(change.uptimeMillis, Offset(0f, projected(change.position)))
                    release.moved(change.uptimeMillis, amount)
                    if (!owned) {
                        if (abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(totalX)) break
                        if (abs(totalX) > viewConfiguration.touchSlop && abs(totalX) > abs(totalY)) {
                            val continuing = motion.moving
                            if (totalX <= 0f && !continuing) break
                            owned = true
                            distance = totalX - if (totalX > 0f) viewConfiguration.touchSlop else -viewConfiguration.touchSlop
                            if (latestCanStart.value() && motion.mounted && motion.phase != MotionPhase.PREPARING) {
                                // 封存侧条当前补偿必须早于 beginDrag 取消上一轮结算。
                                startingProgress = motion.value
                                turn?.let(flight::beginTurnDrag)
                                flight.beginDrag(activeRotation, startingProgress)
                                dragging = motion.beginDrag()
                                if (dragging) latestDragStarted.value()
                            }
                        }
                    } else distance = (distance + amount).coerceIn((startingProgress - 1f) * travel, startingProgress * travel)
                    if (owned) {
                        if (dragging) {
                            val progress = startingProgress - distance / travel
                            motion.dragTo(progress)
                            flight.dragTo(progress)
                        }
                        change.consume()
                    }
                    if (!change.pressed) { normalRelease = true; break }
                }
                if (owned) {
                    val velocity = tracker.calculateVelocity().y
                    val intent = release.released(releaseTime, velocity, normalRelease)
                    val progress = if (dragging) motion.value else 1f - distance / travel
                    val dismiss = shouldDismissPlayer(progress, intent / density, normalRelease,
                        displacement.distancePx / density, displacement.reverseDistancePx / density)
                    if (dragging) {
                        motion.releaseDragWithVelocity(-release.settlingVelocity(releaseTime, velocity, normalRelease) / travel,
                            maxVelocity = 6f, releaseTimeNanos = gestureReleaseTimeNanos(releaseTime))
                        flight.resumeTurn()
                        motion.request(!dismiss, rebound = !dismiss, settle = { flight.settle(); flight.awaitTurn() })
                        dragging = false
                    }
                    if (dismiss) latestDismiss.value()
                }
            } finally {
                if (dragging) {
                    motion.releaseDragWithVelocity(0f)
                    flight.resumeTurn()
                    motion.request(true, rebound = true, settle = { flight.settle(); flight.awaitTurn() })
                }
            }
        }
    }
}
