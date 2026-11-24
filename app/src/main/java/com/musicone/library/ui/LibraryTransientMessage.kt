package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/** 音乐库的操作结果提示：内容 1 秒没有变化后淡出并清除，不长期占着页面。 */
@Composable
internal fun LibraryTransientMessage(ui: MusicLibraryUiController, modifier: Modifier = Modifier) {
    val message = ui.message ?: return
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(message) {
        // 内容变化时从头计时，淡出到一半也立即恢复显示。
        alpha.snapTo(1f)
        delay(1_000)
        alpha.animateTo(0f, musicMotion(300))
        if (ui.message == message) ui.message = null
    }
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value })
}
