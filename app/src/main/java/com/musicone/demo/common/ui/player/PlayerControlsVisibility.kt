package com.musicone.demo

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Stable
internal class PlayerControlsVisibilityState(
    private val scope: CoroutineScope,
    initiallyHidden: Boolean = false,
) {
    var progress by mutableFloatStateOf(if (initiallyHidden) 1f else 0f)
        private set
    var hidden by mutableStateOf(initiallyHidden)
        private set
    private var animationJob: Job? = null
    private var animationVelocity = 0f
    private var dragTravelPx = 1f

    fun beginDrag() {
        animationJob?.cancel()
        animationVelocity = 0f
    }

    fun dragBy(distancePx: Float, travelPx: Float) {
        if (travelPx <= 0f) return
        dragTravelPx = travelPx
        progress = (progress + distancePx / travelPx).coerceIn(0f, 1f)
        hidden = false
    }

    fun settle(velocityY: Float = 0f) {
        animationVelocity = (velocityY / dragTravelPx).coerceIn(-3f, 3f)
        animateTo(if (playerControlsShouldHide(progress, velocityY)) 1f else 0f)
    }

    fun show() = animateTo(0f)

    private fun animateTo(target: Float) {
        animationJob?.cancel()
        animationJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                initialVelocity = animationVelocity,
                animationSpec = musicMotion(240),
            ) { value, velocity ->
                progress = value.coerceIn(0f, 1f)
                animationVelocity = velocity
            }
            animationVelocity = 0f
            hidden = target == 1f
        }
    }
}

@Composable
internal fun rememberPlayerControlsVisibilityState(): PlayerControlsVisibilityState {
    val scope = rememberCoroutineScope()
    val saver = remember(scope) {
        Saver<PlayerControlsVisibilityState, Boolean>(
            save = { it.progress >= .5f },
            restore = { PlayerControlsVisibilityState(scope, it) },
        )
    }
    return rememberSaveable(saver = saver) { PlayerControlsVisibilityState(scope) }
}

internal fun Modifier.playerControlsDrag(
    state: PlayerControlsVisibilityState,
    enabled: Boolean = true,
    reserveBottomButtons: Boolean = false,
): Modifier = if (!enabled) this else pointerInput(state, reserveBottomButtons) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 底部歌词和队列按钮留给点击处理，轻微手抖不能触发整组控件下滑。
        if (reserveBottomButtons && down.position.y >= size.height - 58.dp.toPx()) return@awaitEachGesture
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var dragging = false
        try {
            val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                state.beginDrag()
                dragging = true
                state.dragBy(over, size.height.toFloat())
                change.consume()
            }
            if (start != null && dragging) {
                val completed = verticalDrag(start.id) { change ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    state.dragBy(change.position.y - change.previousPosition.y, size.height.toFloat())
                    change.consume()
                }
                state.settle(if (completed) tracker.calculateVelocity().y else 0f)
                dragging = false
            }
        } finally {
            if (dragging) state.settle()
        }
    }
}

@Composable
internal fun PlayerControlsRestoreHandle(
    state: PlayerControlsVisibilityState,
    modifier: Modifier = Modifier,
) {
    if (state.progress < .82f) return
    IconButton(
        onClick = state::show,
        modifier = modifier
            .size(width = 64.dp, height = 48.dp)
            .playerControlsDrag(state)
            .graphicsLayer { alpha = ((state.progress - .82f) / .18f).coerceIn(0f, 1f) },
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowUp,
            contentDescription = "显示播放控件",
            modifier = Modifier.size(30.dp),
            tint = LocalContentColor.current,
        )
    }
}

internal fun playerControlsShouldHide(progress: Float, velocityY: Float): Boolean =
    velocityY > 900f || (velocityY >= -900f && progress >= .36f)

internal fun playerControlsTranslation(progress: Float, heightPx: Float): Float =
    progress.coerceIn(0f, 1f) * heightPx.coerceAtLeast(0f)
