package com.musicone.demo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun DolbyPlaybackSettings() {
    if (DolbyPlaybackPolicy.capabilities.supportsDolbyPlayback) return
    Row(
        Modifier.fillMaxWidth().toggleable(PlaybackOptions.unlockDolbyAtmos, role = Role.Switch,
            onValueChange = PlaybackOptions::unlockDolby).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("解锁 Dolby全景")
            Text("允许尝试未声明支持的设备，不保证可播放；开启 USB 无损输出并连接音频设备时不可用。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MusicOneSettingsSwitch(PlaybackOptions.unlockDolbyAtmos)
    }
}
