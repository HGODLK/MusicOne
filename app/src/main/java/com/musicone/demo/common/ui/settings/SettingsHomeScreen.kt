package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsHomeScreen(state: PlatformSettingsUiState, onBack: () -> Unit, onOpenSources: () -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp, onOpenCachedMusic: () -> Unit = {}) {
    PlaybackSettingsMenuHost {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = bottomInset + 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { SettingsHeader("设置", onBack) }
                item {
                    ListItem(headlineContent = { Text("主音源") },
                        supportingContent = { Text("平台选择与账号管理") },
                        trailingContent = { Text("${if (state.sourceSelected) state.selectedSource.label else "未选择"} ›") },
                        modifier = Modifier.clickable(onClick = onOpenSources))
                }
                item { PlaybackSettings(state.selectedSource) }
                item { DisplaySettings() }
                item { CacheSettings() }
                item {
                    ListItem(headlineContent = { Text("缓存音乐") }, supportingContent = { Text("歌单缓存与离线播放") },
                        trailingContent = { Text("›") }, modifier = Modifier.clickable(onClick = onOpenCachedMusic))
                }
                item { AboutSettings() }
            }
        }
    }
}
