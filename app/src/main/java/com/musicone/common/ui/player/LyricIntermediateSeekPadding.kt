package com.musicone

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*

/** 中间态 seek 期间保留列表底部额外留白，等对齐结束后再收回。 */
@Composable
internal fun rememberLyricIntermediateSeekPaddingFraction(
    preparing: Boolean,
    positionOwned: Boolean,
): Float {
    val fraction = remember { Animatable(1f) }
    var retained by remember { mutableStateOf(false) }
    LyricAnimationEffect(positionOwned, preparing) {
        if (positionOwned && preparing) {
            retained = true
            fraction.snapTo(1f)
        } else if (retained && !positionOwned) {
            if (!preparing) fraction.animateTo(0f,
                musicSpring(stiffness = 210f, visibilityThreshold = .0001f))
            retained = false
        }
    }
    return when {
        positionOwned && preparing -> 1f
        retained -> fraction.value
        preparing -> 1f
        else -> 0f
    }
}
