package com.musicone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val PLAYBACK_NOTICE_VISIBLE_MS = 3_000L

/** 播放失败提示通道；没有前台收集者时直接丢弃，后台发生的失败回到前台也不补弹。 */
internal object PlaybackFailureNotices {
    private val events = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = events.asSharedFlow()
    fun show(message: String) { events.tryEmit(message) }
}

/** 页面层上报提示的底部避让与播放器画布方向；由主内容按当前场景更新。 */
internal class PlaybackNoticePlacement {
    var bottomInset by mutableStateOf(0.dp)
    var rotation by mutableIntStateOf(0)
    fun update(bottomInset: Dp, rotation: Int) {
        this.bottomInset = bottomInset
        this.rotation = rotation
    }
}

internal val LocalPlaybackNoticePlacement = staticCompositionLocalOf { PlaybackNoticePlacement() }

/** 根页避让迷你播放条与底部导航，详情页只避让迷你播放条，全屏播放器贴底并跟随画布旋转。 */
@Composable
internal fun PlaybackNoticePlacementEffect(playerMounted: Boolean, playerRotation: Int, rootVisible: Boolean,
    bars: BottomBarMeasurements) {
    val placement = LocalPlaybackNoticePlacement.current
    SideEffect {
        placement.update(
            bottomInset = when {
                playerMounted -> 24.dp
                rootVisible -> bars.homeInset
                else -> bars.detailInset
            },
            rotation = if (playerMounted) playerRotation else 0,
        )
    }
}

/**
 * 全局底部提示宿主：复用播放设置菜单的模糊玻璃样式，只淡入淡出；
 * 同时只保留一条，新提示原地替换并重新计时，提示层不拦截任何触摸。
 */
@Composable
internal fun PlaybackFailureNoticeHost(content: @Composable () -> Unit) {
    val placement = remember { PlaybackNoticePlacement() }
    val backdrop = rememberGraphicsLayer()
    var hostBounds by remember { mutableStateOf(Rect.Zero) }
    var message by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                PlaybackFailureNotices.messages.collect { text ->
                    message = text
                    visible = true
                    revision++
                }
            } finally {
                // 离开前台时收起，回来不残留旧提示。
                visible = false
            }
        }
    }
    LaunchedEffect(revision) {
        if (!visible) return@LaunchedEffect
        delay(PLAYBACK_NOTICE_VISIBLE_MS)
        visible = false
    }

    CompositionLocalProvider(LocalPlaybackNoticePlacement provides placement) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { hostBounds = it.boundsInRoot() }) {
            // 提示可见期间持续采样下方页面，歌词滚动等动态内容也能透过玻璃。
            Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, visible, captureContinuously = true)) { content() }
            Box(Modifier.fillMaxSize().then(PassThroughPointerElement)) {
                PlayerMenuWindow(placement.rotation, hostBounds) {
                    val safe = WindowInsets.safeDrawing.asPaddingValues()
                    val bottom = maxOf(placement.bottomInset, safe.calculateBottomPadding()) + 12.dp
                    AnimatedVisibility(
                        visible = visible,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottom),
                        enter = fadeIn(musicMotion(220)),
                        exit = fadeOut(musicMotion(260)),
                    ) {
                        MusicOneBackdropGlass(
                            backdropLayer = backdrop,
                            backdropBounds = hostBounds,
                            modifier = Modifier.widthIn(max = 420.dp),
                            shape = RoundedCornerShape(28.dp),
                            blurRadius = 24.dp,
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .66f),
                            fallbackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)),
                        ) {
                            Text(
                                message,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 提示层与下方页面共享触摸，玻璃内部的 Surface 不会吞掉点击。 */
private object PassThroughPointerElement : ModifierNodeElement<PassThroughPointerNode>() {
    override fun create() = PassThroughPointerNode()
    override fun update(node: PassThroughPointerNode) = Unit
    override fun hashCode() = javaClass.hashCode()
    override fun equals(other: Any?) = other === this
}

private class PassThroughPointerNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) = Unit
    override fun onCancelPointerInput() = Unit
    override fun sharePointerInputWithSiblings() = true
}
