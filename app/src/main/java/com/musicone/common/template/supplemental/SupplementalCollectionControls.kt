package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.sdk.*

/** 补充音源首页与包自定义页面共享账号和远端集合展示。 */
@Composable
internal fun SupplementalAccountHeader(account: SourceAccount?, sourceName: String, onManage: () -> Unit) {
    LibrarySettingsGroup {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteArtwork(account?.avatarUrl.orEmpty(), 0L, 0L, account?.displayName?.take(1).orEmpty(),
                Modifier.size(44.dp), 20.sp, shape = CircleShape)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(account?.displayName ?: "尚未登录", style = MaterialTheme.typography.titleSmall)
                Text(sourceName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onManage) { Icon(Icons.Default.ManageAccounts, "管理账号") }
        }
    }
}

@Composable
internal fun SupplementalCollectionCard(folder: SourceRemoteCollection, loading: Boolean, artworkRevision: Long?,
    modifier: Modifier, onOpen: () -> Unit, onPlay: () -> Unit, showPlay: Boolean = true, openEnabled: Boolean = true) {
    Box(modifier) {
        MainSourcePlaylistCard(folder.asSupplementalPlaylist(), onOpen, onPlay, Modifier.fillMaxWidth(), width = 1280.dp,
            sharedTransition = false, sourceKey = "source-folder:${folder.identity.sourceId}:${folder.identity.remoteId}",
            artworkVersion = artworkRevision, showPlay = showPlay, openEnabled = openEnabled)
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(24.dp),
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
internal fun SupplementalCollectionHeader(folder: SourceRemoteCollection, sourceName: String, playing: Boolean,
    subscribing: Boolean, subscribed: Boolean, canPlay: Boolean, canSubscribe: Boolean,
    onPlay: () -> Unit, onSubscribe: () -> Unit, onRefresh: () -> Unit, onRemove: () -> Unit,
    showPlay: Boolean = true) {
    val menu = LocalPlaybackSettingsMenu.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val coverSize = if (maxWidth >= 600.dp) 152.dp else 112.dp
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            RemoteArtwork(folder.artwork.url.orEmpty(), folder.artwork.startArgb, folder.artwork.endArgb, folder.artwork.mark,
                Modifier.size(coverSize), 36.sp, RoundedCornerShape(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(folder.title, style = MaterialTheme.typography.titleLarge)
                Text(listOfNotNull(folder.count?.let { "$it 个内容" }, sourceName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showPlay) LibraryActionButton(if (playing) "正在读取…" else "播放全部", onPlay, icon = Icons.Default.PlayArrow,
                        enabled = canPlay && !playing && folder.count != 0)
                    if (canSubscribe) LibraryActionButton(
                        if (subscribing) "正在同步…" else if (!subscribed) "订阅歌单" else "已订阅",
                        { if (!subscribed) onSubscribe() else menu?.open("收藏夹订阅", Offset.Zero, listOf(
                            PlaybackSettingsMenuOption("刷新订阅", false, select = onRefresh),
                            PlaybackSettingsMenuOption("取消订阅（保留歌曲）", false, select = onRemove),
                        )) }, icon = if (!subscribed) Icons.Default.Add else Icons.Default.Check,
                        enabled = !subscribing, primary = false)
                }
            }
        }
    }
    Text("收藏内容", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp))
}
