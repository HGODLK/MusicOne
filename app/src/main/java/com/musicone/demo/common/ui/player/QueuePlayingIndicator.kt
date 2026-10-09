package com.musicone.demo

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.sin

/** 三根柔和音量条；暂停时回落，减弱动态时使用静态播放标识。 */
@Composable
internal fun QueuePlayingIndicator(playing: Boolean, color: Color = Color.White) {
    val phase = remember { Animatable(0f) }
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, musicMotion(240), label = "队列音量回落")
    val reduce = ExperiencePreferences.options.reduceMotion
    LaunchedEffect(playing, reduce) {
        if (playing && !reduce) while (isActive) {
            phase.snapTo(0f)
            phase.animateTo(6.283185f, tween(1200, easing = LinearEasing))
        }
    }
    Canvas(Modifier.size(22.dp).semantics { contentDescription = if (playing) "正在播放" else "已暂停" }) {
        repeat(3) { index ->
            val wave = if (reduce) .55f else (sin(phase.value + index * 2f) + 1f) * .5f
            val height = size.height * (.18f + .72f * wave * amplitude)
            drawRoundRect(color, Offset(index * size.width / 3f, (size.height - height) / 2f),
                Size(size.width / 5f, height), CornerRadius(2.dp.toPx()))
        }
    }
}
