package com.musicone.demo

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.isActive
import kotlin.math.sin

private val radioWaveHeights = floatArrayOf(32f, 56f, 82f, 104f, 88f, 62f, 38f)
private val radioWaveAlphas = floatArrayOf(.42f, .58f, .76f, 1f, 1f, 1f, 1f)

/** 斜向声波只改变绘制高度，爱心、标题与卡片布局始终保持稳定。 */
@Composable
internal fun QqRadioWaveArtwork(playing: Boolean, visible: Boolean, color: Color, modifier: Modifier) {
    val motion = rememberSaveable(saver = Saver<QqRadioWaveMotion, Float>(
        save = { it.phase }, restore = { QqRadioWaveMotion(playing, it) },
    )) { QqRadioWaveMotion(playing) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reduced = ExperiencePreferences.options.reduceMotion
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    LaunchedEffect(lifecycle, visible, playing, reduced, durationScale) {
        if (!visible || reduced || durationScale == 0f) {
            motion.settle(playing)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            motion.request(playing)
            var previous = withFrameNanos { it }
            while (isActive && motion.running) {
                val now = withFrameNanos { it }
                motion.advance((now - previous).coerceAtLeast(0L) / 1_000_000_000.0 / durationScale)
                previous = now
            }
        }
    }
    Canvas(modifier) {
        // 同比例缩放图形，底部预留标题空间；圆头自然越出右边缘，不作平直裁断。
        val stage = (size.height - 64.dp.toPx()).coerceAtLeast(0f)
        val scale = minOf(size.width / 216.dp.toPx(), stage / 160.dp.toPx())
        if (scale <= 0f) return@Canvas
        val center = Offset(size.width - 48.dp.toPx() * scale, stage * .6875f)
        val width = 16.dp.toPx() * scale
        val step = 24.dp.toPx() * scale
        val phase = motion.phase * (Math.PI.toFloat() / 180f) * 4f
        val energy = if (reduced || durationScale == 0f) 0f else motion.velocity / QqRadioWaveMotion.CRUISE_SPEED
        rotate(-22f, center) {
            for (index in radioWaveHeights.indices) {
                val height = (radioWaveHeights[index] + energy * 5f * sin(phase + index * .8f)) * 1.dp.toPx() * scale
                drawRoundRect(color.copy(alpha = radioWaveAlphas[index]),
                    topLeft = Offset(center.x + (index - 3) * step - width / 2f, center.y - height / 2f),
                    size = Size(width, height), cornerRadius = CornerRadius(width / 2f))
            }
        }
    }
}
