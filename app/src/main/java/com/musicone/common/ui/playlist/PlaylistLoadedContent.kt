package com.musicone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal val LocalPlaylistLoadedContent = staticCompositionLocalOf { false }

/** 歌单简介等异步文字加载回来时做淡入衔接，不重建整页和播放按钮。 */
@Composable
internal fun PlaylistLoadedValue(value: String, content: @Composable (String) -> Unit) {
    if (!LocalPlaylistLoadedContent.current) {
        content(value)
        return
    }
    val shown = LocalPlaylistMotion.current?.phase == MotionPhase.SHOWN
    val offset = with(LocalDensity.current) { 24.dp.roundToPx() }
    AnimatedContent(
        targetState = value,
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
        transitionSpec = {
            val duration = if (shown) 300 else 0
            (fadeIn(musicMotion(duration)) + slideInVertically(musicMotion(duration)) { offset }) togetherWith
                fadeOut(musicMotion(if (shown) 180 else 0)) using
                SizeTransform { _, _ -> musicMotion(duration) }
        },
        label = "歌单加载文字衔接",
    ) { content(it) }
}
