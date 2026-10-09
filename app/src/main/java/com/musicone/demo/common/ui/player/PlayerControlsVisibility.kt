package com.musicone.demo

import android.os.SystemClock
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    var hintRevision by mutableIntStateOf(0)
        private set
    private var animationJob: Job? = null
    private var animationVelocity = 0f
    private var dragTravelPx = 1f
    private var dragStartedHidden = false

    fun beginDrag() {
        dragStartedHidden = hidden || progress >= .82f
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
        val fromHidden = dragStartedHidden
        dragStartedHidden = false
        animateTo(if (playerControlsShouldHide(progress, velocityY, fromHidden)) 1f else 0f)
    }

    fun show() = animateTo(0f)

    fun revealHint() {
        if (hidden) hintRevision++
    }

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

/** 只观察点击，不消费事件；控件已收起时任意触摸都重启箭头提示。 */
internal fun Modifier.playerControlsHintTouches(state: PlayerControlsVisibilityState): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            state.revealHint()
        }
    }

@Composable
internal fun PlayerControlsRestoreHandle(
    state: PlayerControlsVisibilityState,
    modifier: Modifier = Modifier,
) {
    if (state.progress < .82f) return
    var hintVisible by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state.hidden, state.hintRevision) {
        hintVisible = state.hidden
        if (state.hidden) {
            delay(3_000)
            hintVisible = false
        }
    }
    val visible = state.hidden && hintVisible
    val opacity by animateFloatAsState(if (visible) 1f else 0f, animationSpec = tween(200), label = "上箭头显隐")
    val breath = if (visible && !ExperiencePreferences.options.reduceMotion) {
        val transition = rememberInfiniteTransition(label = "上箭头呼吸")
        val scale by transition.animateFloat(
            initialValue = .94f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "上箭头缩放",
        )
        val travelPx = with(LocalDensity.current) { 2.dp.toPx() }
        val offsetY by transition.animateFloat(
            initialValue = -travelPx,
            targetValue = travelPx,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "上箭头上下浮动",
        )
        scale to offsetY
    } else 1f to 0f
    IconButton(
        onClick = state::show,
        enabled = visible,
        modifier = modifier
            .size(width = 64.dp, height = 48.dp)
            .playerControlsDrag(state)
            .graphicsLayer { alpha = ((state.progress - .82f) / .18f).coerceIn(0f, 1f) * opacity },
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowUp,
            contentDescription = "显示播放控件",
            modifier = Modifier.size(30.dp).graphicsLayer {
                scaleX = breath.first
                scaleY = breath.first
                translationY = breath.second
            },
            tint = LocalContentColor.current,
        )
    }
}

internal fun playerControlsShouldHide(progress: Float, velocityY: Float, fromHidden: Boolean = false): Boolean =
    if (fromHidden) {
        velocityY > 900f || (velocityY >= -300f && progress > .82f)
    } else {
        velocityY > 900f || (velocityY >= -900f && progress >= .36f)
    }

internal fun playerControlsTranslation(progress: Float, heightPx: Float): Float =
    progress.coerceIn(0f, 1f) * heightPx.coerceAtLeast(0f)
