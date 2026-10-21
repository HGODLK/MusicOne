package com.musicone.demo

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
internal fun HyperOsLyricsSettings() {
    val context = LocalContext.current
    if (!remember(context) { HyperOsIslandAccess.supported(context) }) return
    remember(context) { HyperOsLyricsOptions.initialize(context); true }
    val enabled by HyperOsLyricsOptions.enabled.collectAsState()
    val standard by HyperOsLyricsOptions.standardTemplate.collectAsState()
    val status by HyperOsIslandDiagnostics.state.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        HyperOsLyricsOptions.setEnabled(granted)
    }
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(enabled, role = Role.Switch, onValueChange = { value ->
                    if (value && Build.VERSION.SDK_INT >= 33 && !HyperOsIslandAccess.notificationsAllowed(context)) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else HyperOsLyricsOptions.setEnabled(value)
                }).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("超级岛歌词", style = MaterialTheme.typography.titleMedium)
                Text("HyperOS超级岛歌词，长句分段显示。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MusicOneSettingsSwitch(enabled)
        }
        if (enabled) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(standard, role = Role.Switch, onValueChange = HyperOsLyricsOptions::setStandardTemplate)
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("标准模板兼容模式", style = MaterialTheme.typography.titleMedium)
                    Text("歌词不显示时可切换对照，展开卡片使用标准样式。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MusicOneSettingsSwitch(standard)
            }
            Text(status.text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            HyperOsIslandProbeSettings()
        }
    }
}
