package com.musicone

import androidx.compose.runtime.Composable

/** USB 独占的附属开关：音量交给设备硬件调节；无损输出关闭时随之收起。 */
@Composable
internal fun UsbHardwareVolumeSetting() {
    SettingsReveal(PlaybackOptions.usbLosslessOutput) {
        val subtitle = when (UsbHardwareVolumeStatus.supported) {
            false -> "当前设备不支持，已回退为软件调节"
            else -> "直接调节设备硬件音量；若无声或爆音请关闭"
        }
        SettingsSwitchRow("由 USB 设备调节音量", subtitle, PlaybackOptions.usbHardwareVolume, PlaybackOptions::usbHardwareVolume)
    }
}
