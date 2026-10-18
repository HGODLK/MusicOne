package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp

/** 底部恢复区优先识别上滑；监听常驻父容器，不随箭头淡出或控件展开而撤下。 */
@Composable
internal fun Modifier.playerControlsRestoreGesture(
    state: PlayerControlsVisibilityState,
    enabled: Boolean,
    controlsHeightPx: () -> Float,
): Modifier {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestHeight by rememberUpdatedState(controlsHeightPx)
    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!latestEnabled || state.progress < .82f || down.position.y < size.height - 48.dp.toPx())
                return@awaitEachGesture
            val travel = latestHeight().coerceAtLeast(1f)
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            val release = GestureReleaseVelocity(down.uptimeMillis, density)
            var dragging = false
            try {
                val end = awaitOwnedVerticalGesture(down, tracker, release,
                    begin = { over ->
                        (over < 0f).also { accepted ->
                            if (accepted) {
                                state.beginDrag()
                                dragging = true
                            }
                        }
                    },
                    move = { state.dragBy(it, travel) },
                )
                if (end != null && dragging) {
                    val velocity = release.released(end.timeMillis, tracker.calculateVelocity().y, end.completed)
                    state.settle(velocity, density, release.effectiveDistancePx / density, end.completed)
                    dragging = false
                }
            } finally {
                if (dragging) state.settle(releasedNormally = false)
            }
        }
    }
}
