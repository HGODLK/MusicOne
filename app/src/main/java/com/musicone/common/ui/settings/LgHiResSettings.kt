package com.musicone

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun LgHiResSettings() {
    if (!isLgAudioDevice(Build.MANUFACTURER)) return
    val context = LocalContext.current
    remember { LgHiResPreferences.initialize(context); true }
    val enabled by LgHiResPreferences.enabled.collectAsState()
    val detail by PlaybackOutputStatus.lgDetail.collectAsState()
    val lg by PlaybackOutputStatus.lg.collectAsState()
    val system by PlaybackOutputStatus.system.collectAsState()
    val usb by UsbAudioOutputStatus.current.collectAsState()
    val description = when {
        !enabled -> lgHiResIdleDescription(Build.MODEL)
        lg != null -> playbackOutputDescription(null, lg, UsbAudioOutputSnapshot())
        usb.route == UsbAudioOutputRoute.DIRECT_UAC2 || usb.route == UsbAudioOutputRoute.SYSTEM_BIT_PERFECT -> "USB 输出"
        system != null -> playbackOutputDescription(system, null, UsbAudioOutputSnapshot())
        else -> detail
    }
    SettingsSwitchRow("LG Hi-Res 直通输出", description, enabled, LgHiResPreferences::setEnabled)
}
