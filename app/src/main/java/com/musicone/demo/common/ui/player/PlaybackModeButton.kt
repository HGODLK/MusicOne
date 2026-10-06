package com.musicone.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaybackModeButton(
    mode: PlayerPlaybackMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ink = LocalContentColor.current
    val label = if (!enabled) "猜你喜欢固定顺序" else when (mode) {
        PlayerPlaybackMode.LIST -> "列表循环"
        PlayerPlaybackMode.SHUFFLE -> "随机播放"
        PlayerPlaybackMode.SINGLE -> "单曲循环"
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics {
            contentDescription = if (enabled) "$label，点击切换播放模式" else label
        },
        colors = IconButtonDefaults.iconButtonColors(
            disabledContentColor = ink.copy(alpha = .28f),
        ),
    ) {
        AnimatedContent(mode, transitionSpec = {
            (scaleIn(musicMotion(220), initialScale = .65f) + fadeIn(musicMotion(150))) togetherWith
                (scaleOut(musicMotion(140), targetScale = .65f) + fadeOut(musicMotion(100)))
        }, label = "播放模式图标切换") { current ->
            Icon(when (current) {
                PlayerPlaybackMode.LIST -> Icons.Default.Repeat
                PlayerPlaybackMode.SHUFFLE -> Icons.Default.Shuffle
                PlayerPlaybackMode.SINGLE -> Icons.Default.RepeatOne
            }, null, Modifier.size(25.dp))
        }
    }
}
