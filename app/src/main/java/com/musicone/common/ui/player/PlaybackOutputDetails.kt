package com.musicone

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 普通系统输出保留紧凑规格；LG、USB 的信息分别收在对应设置行，避免重复。 */
@Composable
internal fun PlaybackOutputDetails(modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val system by PlaybackOutputStatus.system.collectAsState()
    val usb by UsbAudioOutputStatus.current.collectAsState()
    if (isLgAudioDevice(Build.MANUFACTURER) ||
        usb.route == UsbAudioOutputRoute.DIRECT_UAC2 || usb.route == UsbAudioOutputRoute.SYSTEM_BIT_PERFECT) return
    Text(playbackOutputDescription(system, null, usb), modifier = modifier,
        style = MaterialTheme.typography.bodySmall, color = color)
}
