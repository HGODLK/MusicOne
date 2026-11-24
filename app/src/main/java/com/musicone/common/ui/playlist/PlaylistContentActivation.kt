package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first

@Stable
internal class PlaylistContentActivation(initiallyActivated: Boolean) {
    var mounted by mutableStateOf(initiallyActivated)
        private set
    val entrance = Animatable(if (initiallyActivated) 1f else 0f)

    suspend fun activate() {
        if (mounted) return
        mounted = true
        withFrameNanos { }
        entrance.animateTo(1f, musicMotion(300))
    }
}

internal val LocalPlaylistContentEntrance = staticCompositionLocalOf<() -> Float> { { 1f } }

/** 歌手、专辑页在展开进度到九成时提前挂载内容；歌单页仍等展开结束。 */
internal const val EntityContentEarlyProgress = .9f

/** 首次展开期间只挂载转场骨架；请求照常立即执行，终点后一帧再挂载真实列表。 */
@Composable
internal fun rememberPlaylistContentActivation(playlistId: String, motion: PageMotion,
    earlyProgress: Float? = null): PlaylistContentActivation {
    val activation = remember(playlistId) { PlaylistContentActivation(motion.phase == MotionPhase.SHOWN) }
    if (earlyProgress != null) LaunchedEffect(playlistId, motion, earlyProgress) {
        // 展开曲线收尾很长：表面基本铺满后就挂载并入场，与尾段重叠播放，不等完全贴边。
        snapshotFlow { motion.wantsOpen && motion.phase == MotionPhase.MOVING && motion.value >= earlyProgress }
            .first { it }
        activation.activate()
    }
    LaunchedEffect(playlistId, motion.phase) {
        if (!activation.mounted && motion.phase == MotionPhase.SHOWN) {
            withFrameNanos { }
            if (motion.phase == MotionPhase.SHOWN) activation.activate()
        }
    }
    return activation
}
