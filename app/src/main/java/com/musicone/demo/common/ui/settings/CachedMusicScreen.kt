package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 页面结构始终保留，异步刷新只更新稳定键对应的内容，不重播行入场或替换为加载页。 */
@Composable
internal fun CachedMusicScreen(source: MusicSource, supplied: List<MusicPlaylist>, selectedId: String?,
    onBack: () -> Unit, onOpen: (String) -> Unit, onPlay: (MusicTrack) -> Unit, bottomInset: Dp,
    currentTrackId: String? = null, playing: Boolean = false) {
    val context = LocalContext.current
    val manager = remember { MusicCacheManager.get(context) }
    val state by manager.state.collectAsState()
    val playback = LocalLibraryPlaybackActions.current
    var deleting by remember { mutableStateOf<MusicPlaylist?>(null) }
    var clearing by remember { mutableStateOf<CacheClearMode?>(null) }
    val selection = remember(selectedId) { PlaylistMultiSelection { it.cacheIdentity() } }
    val grid = rememberLazyGridState()
    LaunchedEffect(source, supplied) { manager.configure(source, supplied) }
    val selected = state.playlists.firstOrNull { cachePlaylistKey(it) == selectedId }
    LaunchedEffect(selected?.tracks) { selection.update(selected?.tracks.orEmpty().distinctBy { it.cacheIdentity() }) }
    if (selection.active) BackHandler { selection.close() }
    LaunchedEffect(selectedId) { selected?.let(manager::open) }
    val removeSong: (MusicTrack) -> Unit = { track ->
        deleting = MusicPlaylist("cache-track:${track.id}", track.source, track.title, "", "", 1,
            track.artworkStart, track.artworkEnd, track.artworkMark, listOf(track), track.artworkUrl)
    }
    CompositionLocalProvider(LocalPlaylistMultiSelection provides selection.takeIf { selected != null }) {
    PlaybackSettingsMenuHost(bottomInset = bottomInset) {
        // 复用收藏夹列表的稳定内容作用域，设置列表不继承根歌单的收起进度。
        BiliFolderContentScope {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
                    if (selectedId == null) {
                        CacheManagementHome(state, manager, onBack, onOpen, onPlay, playback.playlist, removeSong,
                            { clearing = it }, bottomInset, currentTrackId, playing)
                    } else {
                    val columns = qqLibraryColumns(maxWidth)
                    val gutter = if (maxWidth >= 600.dp) 32.dp else 20.dp
                    LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = gutter, end = gutter, top = 20.dp, bottom = bottomInset + 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item("header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            SettingsHeader(selected?.title ?: if (selectedId == null) "缓存管理" else "缓存歌单", onBack = {
                                if (selection.active) selection.close() else onBack()
                            })
                        }
                        if (selected != null) {
                            item("playlist-summary", span = { GridItemSpan(maxLineSpan) }, contentType = "summary") {
                                val complete = selected.tracks.count { state.statuses[it.cacheIdentity()]?.startsWith("已缓存") == true }
                                val protected = selected.tracks.count { it.cacheIdentity() in state.protected }
                                LibrarySettingsGroup {
                                    Text("$complete / ${maxOf(selected.count, selected.tracks.size)} 首已缓存 · $protected 首受保护")
                                    CachePlaylistControls(selected, state, manager) { deleting = it }
                                    CachePlaylistSelectionToolbar(selected, manager, selection)
                                }
                            }
                            items(selected.tracks.distinctBy { it.cacheIdentity() }, key = { "song:${it.cacheIdentity()}" },
                                span = { GridItemSpan(maxLineSpan) }, contentType = { "song" }) { track ->
                                CacheSongRow(track, state, onPlay, manager::cacheTrack, removeSong, currentTrackId, playing)
                            }
                        }
                        state.busyTrack?.let { title -> item("task", span = { GridItemSpan(maxLineSpan) }, contentType = "message") {
                            Text("正在缓存：$title", style = MaterialTheme.typography.bodySmall)
                        } }
                        state.message?.let { message -> item("message", span = { GridItemSpan(maxLineSpan) }, contentType = "message") {
                            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    }
                    }
                }
            }
        }
    }
    }
    CacheClearConfirmation(clearing, { clearing = null }, manager::clear)
    deleting?.let { playlist ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${playlist.title}的缓存？") },
            text = { Text("只删除共用音频缓存，不删除歌单、收藏或本地音乐原文件。同曲在其他歌单中也会变为未缓存。") },
            confirmButton = { TextButton(onClick = { deleting = null; manager.remove(playlist) }) { Text("删除缓存") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
