package com.musicone.demo

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
internal fun DevicePlaybackSettings() {
    val context = LocalContext.current
    remember(context) { DevicePlaybackOptions.initialize(context); true }
    val enabled by DevicePlaybackOptions.concurrentPlayback.collectAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(enabled, role = Role.Switch, onValueChange = DevicePlaybackOptions::setConcurrentPlayback)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("允许与其他应用同时播放", style = MaterialTheme.typography.titleMedium)
            Text(
                "仅此设备生效。开启后可与视频、游戏同时出声，播放控件仍可使用；USB 独占输出不支持共享同一设备。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MusicOneSettingsSwitch(enabled)
    }
}
