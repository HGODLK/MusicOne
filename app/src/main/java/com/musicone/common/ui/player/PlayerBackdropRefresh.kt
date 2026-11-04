package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy

/** 只发布采样开关的变化，连续进度不能让首页背景每帧重新录制。 */
@Composable
internal fun rememberPlayerBackdropRefresh(motion: PageMotion): State<Boolean> = remember(motion) {
    playerBackdropRefreshState(motion)
}

internal fun playerBackdropRefreshState(motion: PageMotion): State<Boolean> =
    derivedStateOf(structuralEqualityPolicy()) {
        when (motion.phase) {
            MotionPhase.HIDDEN, MotionPhase.PREPARING -> true
            else -> !motion.wantsOpen && motion.value < .25f
        }
    }
