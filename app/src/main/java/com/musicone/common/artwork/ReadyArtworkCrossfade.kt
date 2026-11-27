package com.musicone

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
    freezeBlend: Boolean = false,
    initialBlend: List<ArtworkBlendSnapshot>? = null,
    respectReduceMotion: Boolean = true,
    // 播放器封面：自然过渡期间按过渡时钟渐变到下一首，暂停即停；其余调用保持原样。
    autoMixDriven: Boolean = false,
    content: @Composable (PlayerArtworkFrame) -> Unit) {
    val latest by rememberUpdatedState(frame)
    val latestDuration by rememberUpdatedState(durationMillis)
    val latestAdoptPreparedFrame by rememberUpdatedState(adoptPreparedFrame)
    val latestDisplayKey by rememberUpdatedState(displayKey)
    val latestFrozen by rememberUpdatedState(freezeBlend)
    val latestRespectReduceMotion by rememberUpdatedState(respectReduceMotion)
    val latestOnDisplayed by rememberUpdatedState(onDisplayed)
    val latestOnBlendSnapshot by rememberUpdatedState(onBlendSnapshot)
    var layers by remember { mutableStateOf(
        (initialBlend ?: if (autoMixDriven) autoMixInitialArtworkBlend(frame) else null)
            ?.map { ArtworkBlend(it.frame, mutableFloatStateOf(it.opacity)) }
            ?: listOf(ArtworkBlend(frame, mutableFloatStateOf(1f)))) }
    var settling by remember { mutableStateOf(layers.size > 1) }
    // 自动混音的长渐变只在开始时上报一次混合状态，避免每一帧都写状态。
    var driven by remember { mutableStateOf(false) }
    val callbackScope = rememberCoroutineScope()
    val lastReported = remember { arrayOfNulls<ArtworkDisplayReport>(1) }
    LaunchedEffect(Unit) {
        // 切到新封面时立刻接管，从当前已经显示的透明度继续渐变，不等上一首的动画播完。
        snapshotFlow { Triple(latest, latestAdoptPreparedFrame, latestFrozen) }.collectLatest { (next, adopt, frozen) ->
            if (frozen) return@collectLatest
            val drive = if (autoMixDriven) autoMixVisualDriveFor(next.identity) else null
            // 自然过渡期间展开播放页不能直接收成目标封面，要接着当前混合比例继续。
            if (adopt && drive == null) {
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
                val spec = if (latestRespectReduceMotion) musicMotion<Float>(latestDuration)
                    else androidx.compose.animation.core.tween<Float>(latestDuration,
                        easing = androidx.compose.animation.core.CubicBezierEasing(.2f, 0f, 0f, 1f))
                val blend = { value: Float ->
                    Snapshot.withMutableSnapshot {
                        transition.forEach { layer ->
                            layer.opacity.floatValue = starts.getValue(layer.frame) * (1f - value) +
                                if (layer.frame == next) value else 0f
                        }
                    }
                }
                var from = 0f
                if (drive != null) {
                    // 已经可见的比例或接手时的过渡进度作为起点，之后完全跟随过渡时钟。
                    val base = maxOf(starts.getValue(next), drive.progress(drive.startClockMs))
                    driven = true
                    try {
                        latestOnBlendSnapshot?.invoke(transition.map { ArtworkBlendSnapshot(it.frame, it.opacity.floatValue) })
                        val completed = drive.follow { clock ->
                            from = autoMixResumedProgress(drive.progress(clock), base)
                            blend(from)
                        }
                        if (completed) from = 1f
                    } finally { driven = false }
                }
                // 普通切歌，或过渡被打断后从当前比例按原速度收尾。
                if (from < 1f) animate(from, 1f, animationSpec = spec) { value, _ -> blend(value) }
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
            // 等这一帧真正画完后再回调通知，确保撤掉上层时底层的新封面已经显示出来。
            callbackScope.launch { latestOnDisplayed?.invoke(drawn) }
        }
        if (settling && !driven) {
            val snapshots = layers.map { ArtworkBlendSnapshot(it.frame, it.opacity.floatValue) }
            latestOnBlendSnapshot?.invoke(snapshots)
        }
    }) {
        // 按权重累加计算每层覆盖的透明度，最底层保持不透明，避免渐变中途透出底色。
        // 先把当前帧的图层列表存成局部变量，防止动画结束删层时下标越界。
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
