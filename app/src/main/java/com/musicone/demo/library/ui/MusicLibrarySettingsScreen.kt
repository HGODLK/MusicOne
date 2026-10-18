package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun MusicLibrarySettingsScreen(onBack: () -> Unit, bottomInset: Dp) {
    val ui = LocalMusicLibraryUi.current
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 840.dp
        val gutter = if (maxWidth >= 600.dp) 32.dp else 20.dp
        LazyColumn(Modifier.widthIn(max = 1120.dp).fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 20.dp, bottom = bottomInset + 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { SettingsHeader("本地音乐库", onBack) }
            if (wide) item {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        LibraryFileImportSettings(ui)
                        LibraryDirectorySettings(ui)
                    }
                    Box(Modifier.weight(1f)) { LibraryArchiveSettings(ui) }
                }
            } else {
                item { LibraryFileImportSettings(ui) }
                item { LibraryDirectorySettings(ui) }
                item { LibraryArchiveSettings(ui) }
            }
            if (ui.busy) item {
                LibrarySettingsGroup {
                    Text(ui.progress.ifBlank { "正在处理…" })
                    LibraryActionButton("取消当前任务", ui::cancel, primary = false)
                }
            }
            ui.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}
