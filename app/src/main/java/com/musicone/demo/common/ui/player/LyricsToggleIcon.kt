package com.musicone.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** 空心引号表示关闭、实心引号表示开启，直接跟随歌词进度，可随手势反向。 */
@Composable
internal fun LyricsToggleIcon(progress: () -> Float, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    Box(modifier) {
        Icon(Icons.Outlined.FormatQuote, null, Modifier.fillMaxSize().graphicsLayer {
            val p = progress().coerceIn(0f, 1f)
            alpha = 1f - p; translationY = 2.dp.toPx() * p
        }, tint = ink)
        Icon(Icons.Filled.FormatQuote, null, Modifier.fillMaxSize().graphicsLayer {
            val p = progress().coerceIn(0f, 1f)
            alpha = p; translationY = -2.dp.toPx() * (1f - p)
        }, tint = ink)
    }
}
