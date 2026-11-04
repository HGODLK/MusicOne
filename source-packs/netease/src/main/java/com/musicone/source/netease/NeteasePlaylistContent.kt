package com.musicone.source.netease

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun NeteasePlaylistContent(owner: NeteasePackageState, actions: NeteaseTrackActions, background: NeteaseBackgroundUiController) {
    val playlist = owner.selectedPlaylist ?: return
    var selecting by remember(playlist.id) { mutableStateOf(false) }
    var selected by remember(playlist.id) { mutableStateOf<Set<String>>(emptySet()) }
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val custom = remember(playlist.id, background.playlistRevision) { NeteaseProfileBackgroundStore.playlistUri(context, playlist.id) }
    val visual = if (custom != null) rememberNeteaseProfileVisual(custom, background.playlistRevision) else background.visual
    val bitmap by rememberArtworkBitmap(playlist.artworkUrl)
    Box(Modifier.fillMaxSize()) {
        NeteaseProfileBackdrop(visual, Modifier.fillMaxSize())
        NeteaseAdaptiveForeground(visual) {
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = (owner.bottomInset + 20).dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SettingsHeader(if (selecting) "已选 ${selected.size} 首" else "歌单") { if (selecting) { selecting = false; selected = emptySet() } else owner.back() } }
                    Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "歌单菜单") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem({ Text("选择歌曲") }, onClick = { menu = false; selecting = true })
                            DropdownMenuItem({ Text("自定义背景") }, onClick = { menu = false; background.choosePlaylist(playlist.id) })
                            if (custom != null) DropdownMenuItem({ Text("恢复全局背景") }, onClick = { menu = false; background.restorePlaylist(playlist.id) })
                            DropdownMenuItem({ Text("刷新歌单") }, onClick = { menu = false; owner.loadPlaylist(false) })
                        }
                    }
                } }
                item { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlaylistSyncedArtwork(bitmap, bitmap == null, playlist.artworkUrl, playlist.artworkStart, playlist.artworkEnd,
                        playlist.artworkMark, Modifier.size(124.dp), 36.sp, RoundedCornerShape(22.dp), playlist.id)
                    Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(playlist.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Text("${playlist.count} 首 · ${playlist.subtitle}", color = visual.secondary, fontSize = 12.sp)
                        if (playlist.description.isNotBlank()) Text(playlist.description, color = visual.secondary, fontSize = 11.sp)
                    }
                } }
                item { Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selecting) {
                        TextButton(onClick = { owner.action {
                            val expected = owner.revision.get(); val cookie = owner.credential(); val vip = owner.account?.hasVipAccess == true
                            val full = withContext(Dispatchers.IO) { owner.api.playlistDetail(playlist.remoteId(), cookie, vip) }
                            owner.check(expected)
                            if (owner.selectedPlaylist?.id == playlist.id) { owner.selectedPlaylist = full.copy(isOwned = playlist.isOwned); owner.playlistPage = NeteaseTrackPage(owner.selectedPlaylist!!); selected = full.tracks.mapTo(linkedSetOf()) { it.id } }
                        } }) { Text("全选") }
                        TextButton(onClick = { selected = emptySet() }) { Text("清空") }
                        TextButton(enabled = selected.isNotEmpty(), onClick = { actions.chooseDestination(playlist.tracks.filter { it.id in selected }) }) { Text("添加到歌单") }
                        TextButton(enabled = selected.isNotEmpty(), onClick = { actions.enqueue(playlist.tracks.filter { it.id in selected }, false) }) { Text("加入队列") }
                    } else {
                        FilledTonalButton(enabled = !owner.loading, onClick = { owner.action {
                            val expected = owner.revision.get(); val cookie = owner.credential(); val vip = owner.account?.hasVipAccess == true
                            val full = withContext(Dispatchers.IO) { owner.api.playlistDetail(playlist.remoteId(), cookie, vip) }
                            owner.check(expected); owner.play(full.tracks)
                        } }) { Icon(Icons.Default.PlayArrow, null); Text("播放全部") }
                        TextButton(onClick = { selecting = true }) { Text("多选") }
                    }
                } }
                if (owner.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                items(playlist.tracks, key = { it.id }) { track ->
                    NeteaseTrackRow(owner, actions, track, selected = if (selecting) track.id in selected else null,
                        onSelect = { if (!selecting) selecting = true; selected = if (track.id in selected) selected - track.id else selected + track.id },
                        onClick = { owner.play(playlist.tracks, playlist.tracks.indexOf(track)) })
                }
                owner.playlistPage?.next?.let { item { TextButton(enabled = !owner.loading, onClick = { owner.loadPlaylist(true) }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } } }
                owner.message?.let { text -> item { Text(text, Modifier.padding(12.dp)) } }
            }
        }
    }
}
