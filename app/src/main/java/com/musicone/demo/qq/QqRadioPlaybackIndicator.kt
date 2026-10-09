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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

internal enum class QqRadioIndicatorState { IDLE, LOADING, PAUSED, PLAYING }

// 后台补充推荐不能打断正在播放或暂停的现有推荐状态。
internal fun qqRadioIndicatorState(active: Boolean, playing: Boolean, loading: Boolean) = when {
    active && playing -> QqRadioIndicatorState.PLAYING
    active -> QqRadioIndicatorState.PAUSED
    loading -> QqRadioIndicatorState.LOADING
    else -> QqRadioIndicatorState.IDLE
}

private enum class QqRadioIndicatorContent { IDLE, LOADING, ACTIVE }

/** 复用队列音柱，让静态播放符号与实际播放状态平滑交接。 */
@Composable
internal fun QqRadioPlaybackIndicator(active: Boolean, playing: Boolean, loading: Boolean, visible: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface) {
    val state = qqRadioIndicatorState(active, playing, loading)
    val content = when (state) {
        QqRadioIndicatorState.PLAYING, QqRadioIndicatorState.PAUSED -> QqRadioIndicatorContent.ACTIVE
        QqRadioIndicatorState.LOADING -> QqRadioIndicatorContent.LOADING
        QqRadioIndicatorState.IDLE -> QqRadioIndicatorContent.IDLE
    }
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    Box(Modifier.size(22.dp).clearAndSetSemantics {
        contentDescription = when (state) {
            QqRadioIndicatorState.LOADING -> "正在加载猜你喜欢"
            QqRadioIndicatorState.PLAYING -> "正在播放猜你喜欢"
            QqRadioIndicatorState.PAUSED -> "猜你喜欢已暂停"
            QqRadioIndicatorState.IDLE -> "播放猜你喜欢"
        }
    }, contentAlignment = Alignment.Center) {
        AnimatedContent(content, transitionSpec = {
            (fadeIn(musicMotion(220)) + scaleIn(musicMotion(240), initialScale = .95f)) togetherWith
                (fadeOut(musicMotion(220)) + scaleOut(musicMotion(220), targetScale = .95f))
        }, contentAlignment = Alignment.Center, label = "猜你喜欢播放状态") { target ->
            when (target) {
                QqRadioIndicatorContent.LOADING -> CircularProgressIndicator(
                    Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
                // 播放与暂停保留同一个音柱对象，让振幅沿用原来的回落动画。
                QqRadioIndicatorContent.ACTIVE -> {
                    if (durationScale == 0f && playing && !ExperiencePreferences.options.reduceMotion) {
                        Icon(Icons.Default.GraphicEq, null, Modifier.size(20.dp), tint = tint)
                    } else QueuePlayingIndicator(active && playing && visible, tint)
                }
                QqRadioIndicatorContent.IDLE -> Icon(Icons.Default.PlayArrow, null,
                    Modifier.size(22.dp), tint = tint)
            }
        }
    }
}
