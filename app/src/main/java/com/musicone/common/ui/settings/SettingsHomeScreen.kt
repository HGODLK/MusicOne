package com.musicone

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
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp, onOpenCachedMusic: () -> Unit = {},
    onOpenLibrary: () -> Unit = {}, onOpenSupplemental: () -> Unit = {}, onOpenAiAssistant: () -> Unit = {},
    onOpenCustomFonts: () -> Unit = {}) {
    PlaybackSettingsMenuHost {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = bottomInset + 24.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                item { SettingsHeader("设置", onBack) }
                // 顶部两个常用入口单独放，下面按一级、二级标题分组。
                item {
                    SettingsCard {
                        SettingsEntryRow("主音源", "平台选择与账号管理",
                            trailing = {
                                // 当前主音源名用主音源色，“›”与其他选项行一致保持灰色。
                                Text(if (state.sourceSelected) state.selectedSource.label else "未选择",
                                    color = MaterialTheme.colorScheme.primary)
                                Text(" ›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            onClick = onOpenSources)
                        LibraryActionRow("Mio", "找歌推荐、品味点评与歌单整理", onClick = onOpenAiAssistant)
                    }
                }
                item {
                    SettingsSection("内容与存储") {
                        SettingsCard {
                            LibraryActionRow("本地音乐库", "文件、关联目录与 .msco 备份", onClick = onOpenLibrary)
                            LibraryActionRow("补充音源", "账号、内容导入与收藏夹订阅", onClick = onOpenSupplemental)
                            CacheManagementEntry(onOpenCachedMusic)
                        }
                    }
                }
                item { PlaybackSettings(state.selectedSource) }
                item { DisplaySettings(onOpenCustomFonts) }
                item { AboutSettings() }
            }
        }
    }
}
