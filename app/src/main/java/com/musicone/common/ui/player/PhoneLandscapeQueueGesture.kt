package com.musicone

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

/** 横屏列表上下滚动，收起手势只沿侧向物理底边，不抢占列表纵轴。 */
@Composable
internal fun Modifier.phoneLandscapeQueueDismissGesture(state: PlayerQueueState, rotation: Int,
    canStart: () -> Boolean): Modifier {
    val allowed = rememberUpdatedState(canStart)
    return pointerInput(state, rotation) {
        val direction = if (rotation > 0) 1f else -1f
        val tracker = VelocityTracker()
        var dragging = false
        var distance = 0f
        fun finish(completed: Boolean) {
            if (!dragging) return
            val motion = state.motion
            val velocity = tracker.calculateVelocity().x * direction
            val open = queueGestureStaysOpen(motion.dragProgress, distance / density,
                velocity / density, 0f, completed)
            motion.releaseDragWithVelocity(
                playerGestureSettlingVelocity(open, -velocity / state.sheetHeightPx.coerceAtLeast(1f)),
                maxVelocity = 6f)
            motion.request(open, rebound = open)
            dragging = false
        }
        try {
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking(); distance = 0f },
                onDragEnd = { finish(true) },
                onDragCancel = { finish(false) },
                onHorizontalDrag = { change, delta ->
                    val outward = delta * direction
                    if (!dragging && outward > 0f && allowed.value() && state.motion.wantsOpen) {
                        dragging = state.motion.beginDrag()
                    }
                    if (dragging) {
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        distance += outward
                        state.motion.dragTo(state.motion.dragProgress - outward / state.sheetHeightPx.coerceAtLeast(1f))
                    }
                },
            )
        } finally { finish(false) }
    }
}
