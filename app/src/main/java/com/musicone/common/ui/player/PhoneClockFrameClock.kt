package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.AndroidUiDispatcher

/** 时钟数字共用最高六十帧的屏幕时钟，较低帧率沿用全局设置。 */
@Composable
internal fun rememberPhoneClockFrameClock(): MonotonicFrameClock = remember {
    AnimationFrameClock(checkNotNull(AndroidUiDispatcher.Main[MonotonicFrameClock]), {
        val configured = ExperiencePreferences.options.uiAnimationFrameRate.fps
        if (configured > 0) minOf(configured, 60) else 60
    })
}
