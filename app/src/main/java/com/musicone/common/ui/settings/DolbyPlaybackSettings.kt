package com.musicone

import androidx.compose.runtime.Composable

@Composable
internal fun DolbyPlaybackSettings() {
    if (DolbyPlaybackPolicy.capabilities.supportsDolbyPlayback) return
    SettingsSwitchRow("解锁杜比全景声", "在未声明支持的设备上尝试启用，可能无法正常播放",
        PlaybackOptions.unlockDolbyAtmos, PlaybackOptions::unlockDolby)
}
