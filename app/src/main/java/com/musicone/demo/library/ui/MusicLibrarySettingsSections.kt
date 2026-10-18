package com.musicone.demo

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryFileImportSettings(ui: MusicLibraryUiController) {
    LibrarySettingsGroup("添加音乐") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LibraryImportTile("选择音乐文件", Icons.Default.AudioFile, !ui.busy, ui.chooseFiles, Modifier.weight(1f))
            LibraryImportTile("关联音乐目录", Icons.Default.CreateNewFolder, !ui.busy,
                { ui.chooseDirectory(null) }, Modifier.weight(1f))
        }
        Text("导入方式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QqSegmentButton("引用原文件", !ui.copyFiles, { ui.copyFiles = false }, Modifier.weight(1f), enabled = !ui.busy)
            QqSegmentButton("复制到应用目录", ui.copyFiles, { ui.copyFiles = true }, Modifier.weight(1f), enabled = !ui.busy)
        }
        Text(if (ui.copyFiles) "复制音频到应用目录，导入时会占用额外空间。" else "直接读取原位置的音频，不额外复制文件。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LibraryDirectorySettings(ui: MusicLibraryUiController) {
    LibrarySettingsGroup {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("关联目录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { ui.chooseDirectory(null) }, enabled = !ui.busy) { Icon(Icons.Default.Add, "关联新的音乐目录") }
        }
        if (ui.snapshot.folders.isEmpty()) Text("尚未关联目录", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        ui.snapshot.folders.forEachIndexed { index, folder ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
            LibraryDirectorySettingsRow(ui, folder)
        }
    }
}

@Composable
private fun LibraryDirectorySettingsRow(ui: MusicLibraryUiController, folder: LibraryFolder) {
    val menu = LocalPlaybackSettingsMenu.current
    var anchor by remember { mutableStateOf(Offset.Zero) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Folder, null, Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(folder.title, style = MaterialTheme.typography.titleSmall)
            val path = remember(folder.uri) { Uri.decode(Uri.parse(folder.uri).lastPathSegment ?: folder.title) }
            Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = {
            menu?.open(folder.title, anchor, listOf(
                PlaybackSettingsMenuOption("刷新目录", false) { ui.refresh(folder) },
                PlaybackSettingsMenuOption("重新关联目录", false) { ui.chooseDirectory(folder) },
                PlaybackSettingsMenuOption("移除目录关联", false) {
                    ui.form = LibraryEditForm("移除「${folder.title}」的关联？歌曲与原文件会保留。", emptyList(), emptyList()) {
                        ui.store.removeFolder(folder.id)
                    }
                },
            ))
        }, enabled = !ui.busy && menu != null,
            modifier = Modifier.onGloballyPositioned { anchor = it.boundsInRoot().center }) {
            Icon(Icons.Default.MoreHoriz, "管理${folder.title}目录")
        }
    }
}

@Composable
internal fun LibraryArchiveSettings(ui: MusicLibraryUiController) {
    var includeAudio by rememberSaveable { mutableStateOf(false) }
    LibrarySettingsGroup("音乐库归档") {
        Text("用 .msco 保存与迁移你的音乐库。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("导入归档", style = MaterialTheme.typography.titleSmall)
        Text("预览后合并，保留现有自定义资料。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LibraryActionButton("选择 .msco 文件", ui.chooseArchive, icon = Icons.Default.Unarchive,
            enabled = !ui.busy, primary = false)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .2f))
        Text("导出音乐库", style = MaterialTheme.typography.titleSmall)
        Text("包含歌单、歌曲资料、封面与歌词。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(includeAudio, { includeAudio = it }, enabled = !ui.busy)
            Text("同时包含本地音频", style = MaterialTheme.typography.bodyMedium)
        }
        LibraryActionButton(if (includeAudio) "导出资料与本地音乐" else "导出资料", { ui.exportArchive(includeAudio) },
            icon = Icons.Default.Archive, enabled = !ui.busy)
        Text(if (includeAudio) "归档包含已导入的本地音频。" else "不包含音频，恢复后可重新关联文件。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
