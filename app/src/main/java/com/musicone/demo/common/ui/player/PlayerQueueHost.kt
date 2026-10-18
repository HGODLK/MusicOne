package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope

/** 首页与完整播放器共用一个队列，开合不改变播放器展开状态。 */
@Stable
internal class PlayerQueueState(scope: CoroutineScope) {
    val motion = PageMotion(scope, false, 320, 260,
        dragAnimation = musicSpring(stiffness = 420f), immediateGestureHandoff = true).apply {
        // 队列没有共享封面，不等待永远不会出现的封面锚点。
        waitForTarget = false
    }
    var sheetHeightPx by mutableFloatStateOf(1f)
        private set
    val visible get() = motion.mounted
    private var openingDistancePx = 0f
    private var openingTravelPx = 1f

    fun updateHeight(height: Int) { sheetHeightPx = height.toFloat().coerceAtLeast(1f) }
    fun open() { if (!motion.wantsOpen) motion.request(true) }
    fun dismiss() { motion.request(false) }

    fun beginOpeningDrag(): Boolean {
        if (sheetHeightPx <= 1f || !motion.beginOpeningDrag()) return false
        openingDistancePx = 0f
        openingTravelPx = sheetHeightPx
        return true
    }

    fun dragOpeningBy(deltaY: Float) {
        openingDistancePx = (openingDistancePx - deltaY).coerceIn(0f, openingTravelPx)
        motion.dragTo(openingDistancePx / openingTravelPx)
    }

    fun finishOpeningDrag(open: Boolean, velocityY: Float, releaseTimeNanos: Long = System.nanoTime()) {
        motion.releaseDragWithVelocity(playerGestureSettlingVelocity(open, -velocityY / openingTravelPx),
            maxVelocity = 6f, releaseTimeNanos = releaseTimeNanos)
        motion.request(open, rebound = !open)
    }
}

@Composable
internal fun rememberPlayerQueueState(): PlayerQueueState {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerQueueState(scope) }
}

internal val LocalPlayerQueueState = staticCompositionLocalOf<PlayerQueueState> {
    error("播放列表宿主尚未挂载")
}

internal fun playerQueueSheetHeight(availableHeight: Float): Float {
    val available = availableHeight.coerceAtLeast(0f)
    return (available * .8f).coerceIn(minOf(320f, available), minOf(680f, available))
}

/** 保持首页原位，退出动画完成后才移除遮罩与队列。 */
@Composable
internal fun PlayerQueueHost(queueState: PlayerQueueState,
    viewModel: MusicPlaybackRuntime, modifier: Modifier = Modifier) {
    val motion = queueState.motion
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()
        .onGloballyPositioned { motion.updateHost(it.boundsInRoot()) }) {
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val height = playerQueueSheetHeight((maxHeight - topInset - 8.dp).value).dp
        val density = LocalDensity.current
        // 关闭时只准备真实行程，不挂载列表，让首次上滑也从第一帧跟手。
        SideEffect { queueState.updateHeight(with(density) { height.roundToPx() }) }
        if (!queueState.visible) return@BoxWithConstraints
        val state by rememberPlayerQueuePresentation(viewModel.state)
        BackHandler { queueState.dismiss() }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = motion.value }
            .background(Color.Black.copy(alpha = .52f))
            .clickable(remember { MutableInteractionSource() }, indication = null,
                onClick = queueState::dismiss))
        Box(Modifier.align(Alignment.BottomCenter).width(minOf(maxWidth, 560.dp)).height(height)
            .onSizeChanged { queueState.updateHeight(it.height) }
            .graphicsLayer { translationY = (1f - motion.value) * size.height }
            .semantics {
                paneTitle = "播放列表"
                dismiss { queueState.dismiss(); true }
            }) {
            PlayerQueueSheet(state, viewModel, queueState)
        }
    }
}
