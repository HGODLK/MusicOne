package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** 首批已在后台就绪也从首帧接续；列表回收重建不重复播放已完成的入场。 */
@Composable
internal fun SearchResultEntrance(content: @Composable () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (shown) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!shown) { withFrameNanos { }; progress.animateTo(1f, musicMotion(360)); shown = true }
    }
    Box(Modifier.graphicsLayer {
        alpha = progress.value
        translationY = 16.dp.toPx() * (1f - progress.value)
    }) { content() }
}
