package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** 隐藏和展开准备阶段直接同步尺寸，转场期间冻结，可见后才播放状态缩放。 */
@Stable
internal class PlayerCoverScaleState(playing: Boolean) {
    private val scale = Animatable(targetScale(playing))

    fun displayedScale(playing: Boolean, phase: MotionPhase, visible: Boolean): Float =
        if (!visible || phase == MotionPhase.HIDDEN || phase == MotionPhase.PREPARING) targetScale(playing)
        else scale.value

    suspend fun update(playing: Boolean, phase: MotionPhase, visible: Boolean) {
        val target = targetScale(playing)
        when {
            !visible || phase == MotionPhase.HIDDEN || phase == MotionPhase.PREPARING -> scale.snapTo(target)
            phase == MotionPhase.SHOWN -> scale.animateTo(target, musicMotion(320))
            else -> Unit
        }
    }

    private fun targetScale(playing: Boolean): Float = if (playing) 1f else .78f
}

@Composable
internal fun rememberPlayerCoverScale(playing: Boolean, motion: PageMotion): State<Float> {
    val scale = remember { PlayerCoverScaleState(playing) }
    val visible = LocalPlayerVisible.current
    val latestPlaying = rememberUpdatedState(playing)
    val latestVisible = rememberUpdatedState(visible)
    LaunchedEffect(scale, playing, motion.phase, visible) {
        scale.update(playing, motion.phase, visible)
    }
    // 首次准备布局立即使用目标尺寸，不能等协程执行后才修正共享元素锚点。
    return remember(scale, motion) {
        derivedStateOf { scale.displayedScale(latestPlaying.value, motion.phase, latestVisible.value) }
    }
}
