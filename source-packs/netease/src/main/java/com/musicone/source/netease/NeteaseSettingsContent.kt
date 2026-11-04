package com.musicone.source.netease

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp

@Composable internal fun NeteaseSettingsContent(owner: NeteasePackageState, authentication: NeteaseSourceAuthentication) {
    val preferences = remember { owner.environment.context.getSharedPreferences("netease_settings", 0) }
    var selected by remember { mutableStateOf(preferences.getString("quality", NeteaseQuality.LOSSLESS.name)) }
    var confirmLogout by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SettingsHeader("网易云音乐设置") { owner.back() } }
        item { Text("首选音质", style = MaterialTheme.typography.titleLarge) }
        items(NETEASE_AUDIO_QUALITIES.size) { index -> val quality = NETEASE_AUDIO_QUALITIES[index]
            Row(Modifier.fillMaxWidth().selectable(selected == quality.name) { selected = quality.name; check(preferences.edit().putString("quality", quality.name).commit()) },
                verticalAlignment = Alignment.CenterVertically) { RadioButton(selected == quality.name, onClick = null); Text(quality.label, Modifier.padding(12.dp)) }
        }
        item { Text("可播放音质仍取决于歌曲版权、账号权限和真实返回的音频规格。") }
        item { TextButton(onClick = { owner.open("authentication") }) { Text(if (owner.account == null) "登录网易云音乐" else "切换账号") } }
        if (owner.account != null) item { TextButton(onClick = { confirmLogout = true }) { Text("退出登录") } }
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出网易云音乐？") },
        text = { Text("仅退出此音源，保留已完成缓存、歌单关联及用户资料。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; owner.action { authentication.logout() } }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}
