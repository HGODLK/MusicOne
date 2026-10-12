package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*

/** 寻焦持有末尾行程，实际落位后再收回；普通开合继续使用原留白规则。 */
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
