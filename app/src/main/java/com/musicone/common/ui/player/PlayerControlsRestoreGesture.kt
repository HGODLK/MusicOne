package com.musicone

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
    viewport: () -> Rect? = { null },
): Modifier {
    val latestEnabled by rememberUpdatedState(enabled)
    val latestHeight by rememberUpdatedState(controlsHeightPx)
    val latestViewport by rememberUpdatedState(viewport)
    return pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val bounds = latestViewport() ?: Rect(0f, 0f, size.width.toFloat(), size.height.toFloat())
            if (!latestEnabled || state.progress < .82f || !bounds.contains(down.position) ||
                down.position.y < bounds.bottom - 48.dp.toPx())
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
