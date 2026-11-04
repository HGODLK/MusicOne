package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** 方向准备与控件显隐共用当前实例，源姿态捕获之前不重置隐藏进度。 */
@Composable
internal fun BindPhonePlayerControlsTurn(presentation: PhonePlayerPresentation?, controls: PlayerControlsVisibilityState) {
    DisposableEffect(presentation, controls) {
        presentation?.controlsVisibility = controls
        onDispose {
            presentation?.let { if (it.controlsVisibility === controls) it.controlsVisibility = null }
        }
    }
}
