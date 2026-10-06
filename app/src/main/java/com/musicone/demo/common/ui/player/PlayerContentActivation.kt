package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/** 遮罩下提前构建歌词；未预热的低内存路径仍在展开完成后分帧挂载。 */
@Composable
internal fun rememberPlayerContentActivated(motion: PageMotion): Boolean {
    val prewarming = LocalPlayerPrewarming.current
    var activated by remember { mutableStateOf(prewarming || motion.phase == MotionPhase.SHOWN) }
    LaunchedEffect(motion.phase, prewarming) {
        if (prewarming) activated = true
        if (!activated && motion.phase == MotionPhase.SHOWN) {
            withFrameNanos { }
            activated = true
        }
    }
    return activated
}
