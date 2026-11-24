package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun DevicePlaybackSettings() {
    val context = LocalContext.current
    remember(context) { DevicePlaybackOptions.initialize(context); true }
    val enabled by DevicePlaybackOptions.concurrentPlayback.collectAsState()
    SettingsSwitchRow("允许与其他应用同时播放",
        "可与视频、游戏等同时出声，USB 独占输出时除外",
        enabled, DevicePlaybackOptions::setConcurrentPlayback)
}
