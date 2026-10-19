package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 收藏夹复用现有方形歌单卡，封面打开内容，圆形按钮直接播放。 */
@Composable
internal fun BiliLibraryHome(state: BiliLibraryUiState, model: BiliLibraryViewModel,
    scroll: LazyGridState, bottomInset: Dp, foldersOnly: Boolean = false) {
    val playback = LocalLibraryPlaybackActions.current
    val directory by model.folderDirectory.state.collectAsState()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = qqLibraryColumns(maxWidth)
        val gutter = maxOf(if (maxWidth >= 600.dp) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
        LazyVerticalGrid(GridCells.Fixed(columns), state = scroll,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = bottomInset + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (!foldersOnly) {
                item("account", span = { GridItemSpan(maxLineSpan) }) { BiliAccountCard(state, model) }
                item("link", span = { GridItemSpan(maxLineSpan) }) { BiliLinkEntry(state, model) }
            }
            state.error?.let { error -> item("action-error", span = { GridItemSpan(maxLineSpan) }) { QqInlineMessage(error) } }
            state.message?.let { message -> item("action-message", span = { GridItemSpan(maxLineSpan) }) { QqInlineMessage(message) } }
            item("heading", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("音乐收藏夹", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = model::loadFolders, enabled = !directory.loading) {
                        Icon(Icons.Default.Refresh, "刷新收藏夹")
                    }
                }
            }
            if (directory.loading && directory.folders.isEmpty()) items(columns, key = { "folder-placeholder-$it" }) {
                LibrarySettingsGroup { Spacer(Modifier.fillMaxWidth().aspectRatio(1f)) }
            }
            items(directory.folders, key = { it.id }) { folder ->
                LaunchedEffect(folder.id, directory.coverRevision) { model.loadFolderCover(folder) }
                Box(Modifier.animateItem(fadeInSpec = musicMotion(300), placementSpec = null, fadeOutSpec = musicMotion(180))) {
                    QqPlaylistCard(folder.asPlaylist(), { model.selectFolder(folder) },
                        { if (state.playingFolderId == null) model.playFolder(folder, playback.playlist) },
                        Modifier.fillMaxWidth(), width = 1280.dp, sharedTransition = false,
                        sourceKey = "bili-folder:${folder.id}")
                    if (state.playingFolderId == folder.id) CircularProgressIndicator(
                        Modifier.align(Alignment.TopEnd).padding(12.dp).size(24.dp), color = MaterialTheme.colorScheme.onSurface)
                }
            }
            directory.error?.let { error -> item("directory-error", span = { GridItemSpan(maxLineSpan) }) {
                Column { QqInlineMessage(error); TextButton(onClick = model::loadFolders) { Text("重试") } }
            } }
            if (directory.folders.isEmpty() && !directory.loading && directory.error == null) item("empty", span = { GridItemSpan(maxLineSpan) }) {
                QqInlineMessage("暂无收藏夹，可先通过视频链接导入音乐。")
            }
        }
    }
}

@Composable
private fun BiliAccountCard(state: BiliLibraryUiState, model: BiliLibraryViewModel) {
    val account = state.account ?: return
    LibrarySettingsGroup {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteArtwork(account.avatar, 0L, 0L, account.name.take(1), Modifier.size(44.dp), 20.sp,
                shape = androidx.compose.foundation.shape.CircleShape)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(account.name.ifBlank { "B站用户" }, style = MaterialTheme.typography.titleSmall)
                Text("已登录哔哩哔哩", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { model.openPage(BiliLibraryPage.ACCOUNT) }) { Icon(Icons.Default.ManageAccounts, "管理B站账号") }
        }
    }
}

@Composable
internal fun BiliLinkEntry(state: BiliLibraryUiState, model: BiliLibraryViewModel) {
    LibrarySettingsGroup("通过链接导入") {
        Text("视频链接、b23.tv 短链或 BV 号", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LibraryFormField("视频链接或 BV 号", state.linkInput, model::updateLinkInput, !state.resolvingLink)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            LibraryActionButton(if (state.resolvingLink) "正在解析…" else "解析内容", { model.resolveLink() },
                icon = Icons.AutoMirrored.Filled.ArrowForward, enabled = state.linkInput.isNotBlank() && !state.resolvingLink)
        }
    }
}
