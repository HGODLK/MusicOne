package com.musicone.demo

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/** 同一徽标从播放三角收束为音柱，暂停和切走时反向交接，不卸载后突然出现。 */
@Composable
internal fun BoxScope.QqFeedPlayingIndicator(current: Boolean, playing: Boolean) {
    val active by animateFloatAsState(if (current) 1f else 0f, musicMotion(240), label = "卡片选中")
    val bars by animateFloatAsState(if (current && playing) 1f else 0f, musicMotion(280), label = "播放符号交接")
    val phase = remember { Animatable(0f) }
    val reduced = ExperiencePreferences.options.reduceMotion
    LaunchedEffect(playing, reduced) {
        if (playing && !reduced) while (true) {
            phase.animateTo(6.283185f, tween(1100, easing = LinearEasing))
            phase.snapTo(0f)
        }
    }
    Canvas(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(40.dp)
        .semantics { stateDescription = if (current && playing) "正在播放" else if (current) "已暂停" else "播放" }) {
        drawCircle(lerp(Color.Black.copy(alpha = .32f), QqMusicThemeColor.copy(alpha = .94f), active))
        scale(1f - .18f * bars) {
            val triangle = Path().apply {
                moveTo(size.width * .4f, size.height * .29f)
                lineTo(size.width * .7f, size.height * .5f)
                lineTo(size.width * .4f, size.height * .71f)
                close()
            }
            drawPath(triangle, Color.White, alpha = 1f - bars)
        }
        repeat(3) { index ->
            val wave = .35f + .65f * ((sin(phase.value + index * 2.1f) + 1f) / 2f)
            val height = 18.dp.toPx() * (.16f + .84f * wave * bars)
            val x = center.x + (index - 1) * 6.dp.toPx() - 1.5.dp.toPx()
            drawRoundRect(Color.White, Offset(x, center.y - height / 2f),
                Size(3.dp.toPx(), height), CornerRadius(2.dp.toPx()), alpha = bars)
        }
    }
}
