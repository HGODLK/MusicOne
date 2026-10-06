package com.musicone.demo

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun CachedMusicScreen(source: MusicSource, supplied: List<MusicPlaylist>, selectedId: String?,
    onBack: () -> Unit, onOpen: (String) -> Unit, onPlay: (MusicTrack) -> Unit, bottomInset: Dp) {
    val context = LocalContext.current
    val manager = remember { MusicCacheManager.get(context) }
    val state by manager.state.collectAsState()
    var deleting by remember { mutableStateOf<MusicPlaylist?>(null) }
    LaunchedEffect(source, supplied) { manager.configure(source, supplied) }
    val selected = state.playlists.firstOrNull { it.id == selectedId }
    LaunchedEffect(selectedId, selected?.id) { selected?.let(manager::open) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth().statusBarsPadding().navigationBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = bottomInset + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SettingsHeader(selected?.title ?: "缓存音乐", onBack) }
                item {
                    Text("完整缓存的歌曲可离线播放。按首选音质缓存，不可用时自动选择下一档。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.message?.let { message -> item {
                    Crossfade(message, animationSpec = musicMotion(220), label = "缓存提示") { Text(it) }
                } }
                if (selectedId == null) {
                    if (state.playlists.isEmpty()) item { Text("暂无歌单，联网登录后可选择我喜欢和其他歌单。") }
                    items(state.playlists, key = { it.id }) { playlist ->
                        val completed = playlist.tracks.count { state.statuses[it.id]?.startsWith("已缓存") == true }
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            ListItem(headlineContent = { Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text("$completed / ${maxOf(playlist.count, playlist.tracks.size)} 首已缓存") },
                                trailingContent = { Text("›") }, modifier = Modifier.clickable { onOpen(playlist.id) })
                            CachePlaylistActions(playlist, state.busyPlaylist, manager::cache, manager::cancel,
                                { deleting = it })
                        }
                    }
                } else if (selected != null) {
                    item { CachePlaylistActions(selected, state.busyPlaylist, manager::cache, manager::cancel, { deleting = it }) }
                    if (selected.tracks.isEmpty() && !state.loading) item { Text("尚无歌曲目录，请联网后打开此歌单。") }
                    items(selected.tracks.distinctBy { it.id }, key = { it.id }) { track ->
                        ListItem(headlineContent = { TrackTitle(track, fontSize = MaterialTheme.typography.bodyLarge.fontSize) },
                            supportingContent = { Column {
                                Text(track.artists, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Crossfade(state.statuses[track.id] ?: "未缓存", animationSpec = musicMotion(220), label = "缓存进度") {
                                    Text(it, style = MaterialTheme.typography.labelMedium)
                                }
                            } }, modifier = Modifier.clickable { onPlay(track) })
                    }
                }
            }
        }
    }
    deleting?.let { playlist ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${playlist.title}的缓存？") },
            text = { Text("只删除音频缓存，不删除歌单或收藏。同一歌曲在其他歌单中也会变为未缓存。") },
            confirmButton = { TextButton(onClick = { deleting = null; manager.remove(playlist) }) { Text("删除缓存") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun CachePlaylistActions(playlist: MusicPlaylist, busy: String?, onCache: (MusicPlaylist) -> Unit,
    onCancel: () -> Unit, onDelete: (MusicPlaylist) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { if (busy == playlist.id) onCancel() else onCache(playlist) },
            enabled = busy == null || busy == playlist.id) { Text(if (busy == playlist.id) "停止缓存" else "缓存歌单") }
        TextButton(onClick = { onDelete(playlist) }, enabled = playlist.tracks.isNotEmpty()) { Text("删除缓存") }
    }
}
