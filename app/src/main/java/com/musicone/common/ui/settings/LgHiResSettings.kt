package com.musicone

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

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
    Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch,
        onValueChange = LgHiResPreferences::setEnabled).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("LG Hi-Res 直通输出")
            Text(description,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MusicOneSettingsSwitch(enabled)
    }
}
