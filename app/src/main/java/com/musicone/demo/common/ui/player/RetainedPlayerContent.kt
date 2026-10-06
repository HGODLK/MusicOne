package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

internal val LocalPlayerVisible = staticCompositionLocalOf { true }
internal val LocalPlayerPrewarming = staticCompositionLocalOf { false }

/** 隐藏时保留组合和测量结果，但不放置子树，避免绘制、命中和无障碍节点泄漏。 */
@Composable
internal fun RetainedPlayerContent(
    visible: Boolean,
    prewarming: Boolean,
    retention: PlayerRetentionState,
    content: @Composable () -> Unit,
) {
    val parent = LocalLifecycleOwner.current.lifecycle
    val owner = remember(parent) { PlayerContentLifecycle() }
    val collecting = visible || prewarming
    val latestCollecting by rememberUpdatedState(collecting)
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { _, _ -> owner.update(parent.currentState, latestCollecting) }
        parent.addObserver(observer)
        onDispose {
            parent.removeObserver(observer)
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }
    SideEffect { owner.update(parent.currentState, collecting) }
    val scope = rememberCoroutineScope()
    val drawReported = remember { booleanArrayOf(false) }
    CompositionLocalProvider(
        LocalLifecycleOwner provides owner,
        LocalPlayerVisible provides visible,
        LocalPlayerPrewarming provides prewarming,
    ) {
        Layout(
            content = content,
            modifier = (if (visible) Modifier else Modifier.clearAndSetSemantics { })
                .drawWithContent {
                    drawContent()
                    if (prewarming && !drawReported[0]) {
                        drawReported[0] = true
                        scope.launch {
                            // 至少提交完整绘制，再让启动遮罩退出。
                            repeat(2) { withFrameNanos { } }
                            retention.markWarmed()
                        }
                    }
                },
        ) { measurables, constraints ->
            val children = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (visible || prewarming) children.forEach { it.place(0, 0) }
            }
        }
    }
}

private class PlayerContentLifecycle : LifecycleOwner {
    val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    override val lifecycle: Lifecycle get() = registry

    fun update(parent: Lifecycle.State, visible: Boolean) {
        registry.currentState = if (visible) minOf(parent, Lifecycle.State.RESUMED)
            else minOf(parent, Lifecycle.State.CREATED)
    }
}

/** 没有历史歌曲时只预热通用布局，不写入播放状态或请求音源。 */
internal val PlayerWarmupTrack = MusicTrack(
    id = "player-warmup", source = MusicSource.QQ, title = "", artists = "", album = "",
    durationMs = 0, artworkStart = 0xFF303038, artworkEnd = 0xFF181820,
    artworkMark = "", previewUrl = "", playable = false,
)
