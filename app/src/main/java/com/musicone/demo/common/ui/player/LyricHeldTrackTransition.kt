package com.musicone.demo

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

/** 按住切歌只改变内容权重；任务独立于播放跟随开关，松手不能中断交接。 */
@Stable
internal class LyricHeldTrackTransition {
    var layers by mutableStateOf<List<LyricHeldLayer>>(emptyList())
        private set
    var active by mutableStateOf(false)
        private set
    var preparation by mutableStateOf<LyricHeldWindowPreparation?>(null)
        private set
    private var animation: Job? = null
    private var revision = 0L

    fun request(source: LyricWindow?, target: LyricWindow, scope: CoroutineScope) {
        val starts = (if (active) layers.map { LyricHeldBlend(it.window, it.weight) } else emptyList())
            .filter { it.weight > .001f }.ifEmpty {
            listOfNotNull(source?.let { LyricHeldBlend(it, 1f) })
        }
        val total = starts.sumOf { it.weight.toDouble() }.toFloat()
        val normalized = starts.map { it.copy(weight = it.weight / total.coerceAtLeast(.001f)) }
        val request = ++revision
        animation?.cancel()
        val prepared = LyricHeldWindowPreparation()
        Snapshot.withMutableSnapshot {
            layers = (normalized + LyricHeldBlend(target, 0f)).map { LyricHeldLayer(it.window, it.weight) }
            preparation = prepared
            active = true
        }
        animation = scope.launch {
            prepared.ready.await()
            if (request != revision) return@launch
            target.ready.complete(Unit)
            preparation = null
            val drawingLayers = layers
            animate(0f, 1f, animationSpec = musicMotion<Float>(420)) { fraction, _ ->
                // 权重只在绘制阶段消费，渐变帧不重组整棵歌词列表。
                val weights = lyricHeldBlendWeights(normalized, target, fraction)
                Snapshot.withMutableSnapshot {
                    drawingLayers.forEachIndexed { index, layer -> layer.weight = weights[index].weight }
                }
            }
            if (request == revision) Snapshot.withMutableSnapshot {
                layers = listOf(LyricHeldLayer(target, 1f))
                active = false
            }
        }
    }

    fun weight(window: LyricWindow): Float = layers.firstOrNull { it.window === window }?.weight ?: 0f

    fun cancel() {
        ++revision
        animation?.cancel()
        animation = null
        preparation = null
        layers = emptyList()
        active = false
    }
}

internal fun lyricHeldBlendWeights(starts: List<LyricHeldBlend>, target: LyricWindow,
    progress: Float): List<LyricHeldBlend> {
    val fraction = progress.coerceIn(0f, 1f)
    return starts.map { it.copy(weight = it.weight * (1f - fraction)) } + LyricHeldBlend(target, fraction)
}

/** 透明歌词先在隔离层中按权重相加，再统一经过外层玻璃，避免重叠文字变暗或旧字透底。 */
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
            // 零权重目标仍完成录制与绘制确认，不把布局返回误当成可见就绪。
            drawContent()
            canvas.restore()
        }
    }
}
