package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun BiliFolderHeader(folder: BiliFavFolder, state: BiliLibraryUiState, model: BiliLibraryViewModel) {
    val playback = LocalLibraryPlaybackActions.current
    val menu = LocalPlaybackSettingsMenu.current
    val subscription = state.subscriptions.firstOrNull { it.folderId == folder.id && it.ownerId == state.account?.mid }
    LaunchedEffect(folder.id) { model.loadFolderCover(folder) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val coverSize = if (maxWidth >= 600.dp) 152.dp else 112.dp
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RemoteArtwork(folder.cover, 0L, 0L, "", Modifier.size(coverSize), 36.sp, RoundedCornerShape(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(folder.title, style = MaterialTheme.typography.titleLarge)
                Text("${folder.mediaCount} 个视频 · B站收藏夹", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryActionButton(if (state.playingFolderId == folder.id) "正在读取…" else "播放全部",
                        { model.playFolder(folder, playback.playlist) }, icon = Icons.Default.PlayArrow,
                        enabled = state.playingFolderId == null && folder.mediaCount > 0)
                    LibraryActionButton(if (state.isSubscribing) "正在同步…" else if (subscription == null) "订阅歌单" else "已订阅",
                        {
                            if (subscription == null) model.subscribeFolder(folder) else menu?.open("收藏夹订阅", Offset.Zero, listOf(
                                PlaybackSettingsMenuOption("刷新订阅", false) { model.subscribeFolder(folder) },
                                PlaybackSettingsMenuOption("取消订阅（保留歌曲）", false) { model.stopSubscription(subscription.playlistId) },
                            ))
                        }, icon = if (subscription == null) Icons.Default.Add else Icons.Default.Check,
                        enabled = !state.isSubscribing, primary = false)
                }
            }
        }
    }
    Text("视频内容", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
internal fun BiliImportTarget(state: BiliLibraryUiState, model: BiliLibraryViewModel) {
    val ui = LocalMusicLibraryUi.current
    val menu = LocalPlaybackSettingsMenu.current
    androidx.compose.runtime.LaunchedEffect(state.targetPlaylistId, ui.snapshot.playlists, ui.snapshot.subscriptions) {
        if (ui.snapshot.playlists.isNotEmpty() && ui.snapshot.manualAdditionTargets().none { it.id == state.targetPlaylistId }) {
            model.setTargetPlaylist(UNIFIED_PLAYLIST_ID, "综合歌单")
        }
    }
    LibraryActionRow("添加到：${state.targetPlaylistTitle}", enabled = menu != null) {
        menu?.open("选择导入目标", Offset.Zero, ui.snapshot.manualAdditionTargets().map { playlist ->
            PlaybackSettingsMenuOption(playlist.title, state.targetPlaylistId == playlist.id,
                select = { model.setTargetPlaylist(playlist.id, playlist.title) })
        })
    }
}
