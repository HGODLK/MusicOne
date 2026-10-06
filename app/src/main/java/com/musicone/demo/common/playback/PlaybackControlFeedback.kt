package com.musicone.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun PlaybackControlButton(
    qqFeedback: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalMotion: Dp = 0.dp,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val press = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val motionPx = with(LocalDensity.current) { horizontalMotion.toPx() }
    IconButton(
        onClick = {
            if (qqFeedback) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    press.snapTo(1f)
                    press.animateTo(0f, musicMotion(260))
                }
            }
            onClick()
        },
        enabled = enabled,
        modifier = modifier.graphicsLayer {
            if (qqFeedback) {
                val scale = 1f - press.value * .16f
                scaleX = scale
                scaleY = scale
                translationX = motionPx * press.value
            }
        },
        content = content,
    )
}

@Composable
internal fun PlaybackStateIcon(
    isPlaying: Boolean,
    qqFeedback: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!qqFeedback) {
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (isPlaying) "暂停" else "播放",
            modifier,
        )
        return
    }
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            (fadeIn(musicMotion(150)) + scaleIn(musicMotion(180), initialScale = .68f)) togetherWith
                (fadeOut(musicMotion(100)) + scaleOut(musicMotion(120), targetScale = .68f))
        },
        label = "QQ 播放状态切换",
    ) { playing ->
        Icon(
            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            if (playing) "暂停" else "播放",
            modifier,
        )
    }
}
