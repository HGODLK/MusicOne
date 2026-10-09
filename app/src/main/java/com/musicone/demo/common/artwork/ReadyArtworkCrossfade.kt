package com.musicone.demo

import androidx.compose.animation.core.animate
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
internal fun ReadyArtworkCrossfade(frame: PlayerArtworkFrame, durationMillis: Int, modifier: Modifier = Modifier,
    modulateAlpha: Boolean = false,
    adoptPreparedFrame: Boolean = false,
    displayKey: Any? = null,
    onDisplayed: ((PlayerArtworkFrame) -> Unit)? = null,
    onBlendSnapshot: ((List<ArtworkBlendSnapshot>) -> Unit)? = null,
    content: @Composable (PlayerArtworkFrame) -> Unit) {
    val latest by rememberUpdatedState(frame)
    val latestDuration by rememberUpdatedState(durationMillis)
    val latestAdoptPreparedFrame by rememberUpdatedState(adoptPreparedFrame)
    val latestDisplayKey by rememberUpdatedState(displayKey)
    val latestOnDisplayed by rememberUpdatedState(onDisplayed)
    val latestOnBlendSnapshot by rememberUpdatedState(onBlendSnapshot)
    var layers by remember { mutableStateOf(listOf(ArtworkBlend(frame, mutableFloatStateOf(1f)))) }
    var settling by remember { mutableStateOf(false) }
    val callbackScope = rememberCoroutineScope()
    val lastReported = remember { arrayOfNulls<ArtworkDisplayReport>(1) }
    LaunchedEffect(Unit) {
        // 新目标立刻接管，从当前可见比例续接，不等待旧歌动画播完。
        snapshotFlow { Pair(latest, latestAdoptPreparedFrame) }.collectLatest { (next, adopt) ->
            if (adopt) {
                // 展开播放页交接：直接接管目标封面，立即清除隐藏播放页遗留的上一首图层
                Snapshot.withMutableSnapshot {
                    layers = listOf(ArtworkBlend(next, mutableFloatStateOf(1f)))
                    settling = false
                }
                return@collectLatest
            }
            if (layers.size != 1 || layers.single().frame != next) {
                val starts = layers.filter { it.opacity.floatValue > .001f }
                    .associate { it.frame to it.opacity.floatValue }.toMutableMap()
                val total = starts.values.sum()
                starts.entries.forEach { entry -> entry.setValue(entry.value / total) }
                if (next !in starts) starts[next] = 0f
                val transition = starts.map { (artwork, weight) -> ArtworkBlend(artwork, mutableFloatStateOf(weight)) }
                Snapshot.withMutableSnapshot { layers = transition; settling = true }
                animate(0f, 1f, animationSpec = musicMotion<Float>(latestDuration)) { value, _ ->
                    Snapshot.withMutableSnapshot {
                        transition.forEach { layer ->
                            layer.opacity.floatValue = starts.getValue(layer.frame) * (1f - value) +
                                if (layer.frame == next) value else 0f
                        }
                    }
                }
                Snapshot.withMutableSnapshot {
                    layers = listOf(ArtworkBlend(next, mutableFloatStateOf(1f)))
                    settling = false
                }
            }
        }
    }
    Box(modifier.drawWithContent {
        drawContent()
        val drawn = layers.last().frame
        val report = ArtworkDisplayReport(latestDisplayKey, drawn)
        if (!settling && lastReported[0] != report) {
            lastReported[0] = report
            // 绘制阶段结束后再通知状态机，确保撤层时底层目标封面已经真实可见。
            callbackScope.launch { latestOnDisplayed?.invoke(drawn) }
        }
        if (settling) {
            val snapshots = layers.map { ArtworkBlendSnapshot(it.frame, it.opacity.floatValue) }
            latestOnBlendSnapshot?.invoke(snapshots)
        }
    }) {
        // 按累计权重换算覆盖透明度，底图始终不透明，混合途中不透出白底。
        // 一帧内固定图层快照，避免动画收层时索引读到已经缩短的新列表。
        val drawingLayers = layers
        drawingLayers.forEachIndexed { index, layer ->
            key(layer.frame) {
                Box((if (index == 0) Modifier else Modifier.matchParentSize()).graphicsLayer {
                    // 单一封面层可直接调制透明度，避免为每帧渐变分配离屏缓冲。
                    if (modulateAlpha) compositingStrategy = CompositingStrategy.ModulateAlpha
                    var cumulative = 0f
                    for (position in 0..index) cumulative += drawingLayers[position].opacity.floatValue
                    alpha = if (index == 0) 1f else if (cumulative > 0f) layer.opacity.floatValue / cumulative else 0f
                }) { content(layer.frame) }
            }
        }
    }
}

private data class ArtworkDisplayReport(val key: Any?, val frame: PlayerArtworkFrame)

private data class ArtworkBlend(val frame: PlayerArtworkFrame, val opacity: MutableFloatState)
