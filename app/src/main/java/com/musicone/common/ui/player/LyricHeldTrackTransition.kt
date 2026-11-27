package com.musicone

import androidx.compose.animation.core.animate
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class LyricHeldBlend(val window: LyricWindow, val weight: Float)

@Stable
internal class LyricHeldLayer(val window: LyricWindow, weight: Float) {
    var weight by mutableFloatStateOf(weight)
}

/** 按住手势或转屏期间的渐变切歌控制器：只调整新旧窗口的混合权重，松手不会中断正在进行的渐变。 */
@Stable
internal class LyricHeldTrackTransition {
    var layers by mutableStateOf<List<LyricHeldLayer>>(emptyList())
        private set
    var active by mutableStateOf(false)
        private set
    var preparation by mutableStateOf<LyricHeldWindowPreparation?>(null)
        private set
    var awaitingLayout by mutableStateOf(false)
        private set
    private var animation: Job? = null
    private var revision = 0L
    private val slidingWeights = mutableStateMapOf<LyricWindow, Float>()
    val hasSlidingBlend get() = slidingWeights.isNotEmpty()

    fun request(source: LyricWindow?, target: LyricWindow, scope: CoroutineScope,
        gesturePreparation: Boolean = true, initialSources: List<LyricHeldBlend> = emptyList(),
        parentDeadline: Long = Long.MAX_VALUE, drawEpoch: LyricDrawEpoch? = null, waitForReady: Boolean = true) {
        val starts = (if (active) layers.map { LyricHeldBlend(it.window, it.weight) } else initialSources)
            .filter { it.weight > 0f }.ifEmpty {
            if (active) emptyList() else listOfNotNull(source?.let { LyricHeldBlend(it, 1f) })
        }
        // 保留当前各可见窗口的实际权重，不要重新归一化，防止滑动中的旧窗口突然变暗。
        val normalized = starts
        val request = ++revision
        animation?.cancel()
        val prepared = if (gesturePreparation && waitForReady) LyricHeldWindowPreparation() else null
        Snapshot.withMutableSnapshot {
            layers = (normalized + LyricHeldBlend(target, 0f)).map { LyricHeldLayer(it.window, it.weight) }
            preparation = prepared
            awaitingLayout = true
            active = true
        }
        animation = scope.launch {
            var completed = false
            try {
                val ready = !waitForReady || target.preparationBudget.await(prepared?.ready ?: target.ready, parentDeadline)
                if (request != revision) return@launch
                if (ready && waitForReady) target.ready.complete(Unit)
                else if (!ready) target.recoverDisplay(drawEpoch)
                preparation = null
                awaitingLayout = false
                val drawingLayers = layers
                animate(0f, 1f, animationSpec = musicMotion<Float>(420)) { fraction, _ ->
                    // 权重只在绘制阶段读取，渐变动画不会触发整棵歌词列表重组。
                    val weights = lyricHeldBlendWeights(normalized, target, fraction)
                    Snapshot.withMutableSnapshot {
                        drawingLayers.forEachIndexed { index, layer -> layer.weight = weights[index].weight }
                    }
                }
                if (request == revision) Snapshot.withMutableSnapshot {
                    layers = listOf(LyricHeldLayer(target, 1f))
                    active = false
                }
                completed = true
            } finally {
                // 被新请求替换或主动取消时 revision 已递增；这里只兜底当前任务意外中断的情况。
                if (!completed && request == revision) Snapshot.withMutableSnapshot {
                    target.recoverDisplay(drawEpoch)
                    preparation = null
                    awaitingLayout = false
                    layers = listOf(LyricHeldLayer(target, 1f))
                    active = false
                }
            }
        }
    }

    fun weight(window: LyricWindow): Float = layers.firstOrNull { it.window === window }?.weight ?: 0f

    /** 松手后如果又触发滑动切歌，把当前还在渐变中的窗口连同权重转交给滑出列表继续退场。 */
    fun releaseForSlide(): List<LyricWindow> {
        if (!active) return emptyList()
        val visible = layers.filter { it.weight > 0f }.map { LyricHeldBlend(it.window, it.weight) }
        Snapshot.withMutableSnapshot {
            cancel()
            visible.forEach { slidingWeights[it.window] = it.weight }
        }
        return visible.map { it.window }
    }

    fun slidingWeight(window: LyricWindow): Float? = slidingWeights[window]

    fun forgetSlide(window: LyricWindow) { slidingWeights.remove(window) }

    fun clearSlides() { slidingWeights.clear() }

    fun cancel() {
        ++revision
        animation?.cancel()
        animation = null
        preparation = null
        awaitingLayout = false
        layers = emptyList()
        active = false
    }
}

internal fun lyricHeldBlendWeights(starts: List<LyricHeldBlend>, target: LyricWindow,
    progress: Float): List<LyricHeldBlend> {
    val fraction = progress.coerceIn(0f, 1f)
    return starts.map { it.copy(weight = it.weight * (1f - fraction)) } + LyricHeldBlend(target, fraction)
}

/** 渐变切歌时先在离屏层把新旧歌词按权重相加，再交给外层模糊，避免文字发暗或透底。 */
@Composable
internal fun Modifier.lyricHeldComposite(enabled: () -> Boolean): Modifier {
    val paint = remember { Paint() }
    return drawWithContent {
        if (!enabled()) drawContent() else drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}

@Composable
internal fun Modifier.lyricHeldWeight(weight: () -> Float?): Modifier {
    val paint = remember { Paint().apply { blendMode = BlendMode.Plus } }
    return drawWithContent {
        val fraction = weight()
        if (fraction == null) drawContent() else drawIntoCanvas { canvas ->
            paint.alpha = fraction.coerceIn(0f, 1f)
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            // 即使当前权重为 0 也要正常走完 drawContent，确保新窗口完成首次绘制确认。
            drawContent()
            canvas.restore()
        }
    }
}
