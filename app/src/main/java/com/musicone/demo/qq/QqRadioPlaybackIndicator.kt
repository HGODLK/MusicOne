package com.musicone.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

private enum class QqRadioIndicatorState { IDLE, LOADING, PAUSED, PLAYING }

/** 复用队列音柱，让静态播放符号与实际播放状态平滑交接。 */
@Composable
internal fun QqRadioPlaybackIndicator(active: Boolean, playing: Boolean, loading: Boolean) {
    val state = when {
        loading -> QqRadioIndicatorState.LOADING
        active && playing -> QqRadioIndicatorState.PLAYING
        active -> QqRadioIndicatorState.PAUSED
        else -> QqRadioIndicatorState.IDLE
    }
    val tint = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(22.dp).clearAndSetSemantics {
        contentDescription = when (state) {
            QqRadioIndicatorState.LOADING -> "正在加载猜你喜欢"
            QqRadioIndicatorState.PLAYING -> "正在播放猜你喜欢"
            QqRadioIndicatorState.PAUSED -> "猜你喜欢已暂停"
            QqRadioIndicatorState.IDLE -> "播放猜你喜欢"
        }
    }, contentAlignment = Alignment.Center) {
        AnimatedContent(state, transitionSpec = {
            (fadeIn(musicMotion(220)) + scaleIn(musicMotion(240), initialScale = .75f)) togetherWith
                (fadeOut(musicMotion(160)) + scaleOut(musicMotion(180), targetScale = .75f))
        }, contentAlignment = Alignment.Center, label = "猜你喜欢播放状态") { target ->
            when (target) {
                QqRadioIndicatorState.LOADING -> CircularProgressIndicator(
                    Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF1E8E6B))
                QqRadioIndicatorState.PLAYING -> QueuePlayingIndicator(true, tint)
                QqRadioIndicatorState.PAUSED -> Icon(Icons.Default.GraphicEq, null,
                    Modifier.size(20.dp), tint = tint)
                QqRadioIndicatorState.IDLE -> Icon(Icons.Default.PlayArrow, null,
                    Modifier.size(22.dp), tint = tint)
            }
        }
    }
}
