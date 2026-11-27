package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** “播放行为”里的自然过渡开关，仅保存在本机，默认关闭。 */
@Composable
internal fun AutoMixSettings() {
    val context = LocalContext.current
    remember(context) { AutoMixPreferences.initialize(context); true }
    val enabled by AutoMixPreferences.enabled.collectAsState()
    // 与 USB 独占互斥：对方开着时置灰，已开启的一方始终可以关掉
    val usbExclusive = PlaybackOptions.usbLosslessOutput
    val blocked = !enabled && usbExclusive
    SettingsSwitchRow("自然过渡",
        if (blocked) "USB 独占输出时不可用" else "根据节拍与人声衔接下一首，不同曲风表现有所差异；开启后杜比全景声与 USB 独占输出不可用",
        enabled, enabled = !blocked, onChange = AutoMixPreferences::setEnabled)
}
