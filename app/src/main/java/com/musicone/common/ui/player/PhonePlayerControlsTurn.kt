package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** 暂存播放控件的显隐状态，供转屏准备期间平滑交接。 */
@Composable
internal fun BindPhonePlayerControlsTurn(presentation: PhonePlayerPresentation?, controls: PlayerControlsVisibilityState) {
    DisposableEffect(presentation, controls) {
        presentation?.controlsVisibility = controls
        onDispose {
            presentation?.let { if (it.controlsVisibility === controls) it.controlsVisibility = null }
        }
    }
}
