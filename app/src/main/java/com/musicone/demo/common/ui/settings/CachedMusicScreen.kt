package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sourceFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<MusicPlaylist?>(null) }
    var clearing by remember { mutableStateOf<CacheClearMode?>(null) }
    val selection = remember(selectedId) { PlaylistMultiSelection { it.cacheIdentity() } }
    val grid = rememberLazyGridState()
    val search = rememberPlaylistSearchState("cache-playlists")
    if (selectedId == null && search.expanded) BackHandler { search.close() }
    LaunchedEffect(source, supplied) { manager.configure(source, supplied) }
    val selected = state.playlists.firstOrNull { cachePlaylistKey(it) == selectedId }
    LaunchedEffect(selected?.tracks) { selection.update(selected?.tracks.orEmpty().distinctBy { it.cacheIdentity() }) }
    if (selection.active) BackHandler { selection.close() }
    LaunchedEffect(selectedId) { selected?.let(manager::open) }
    val playlists = remember(state.playlists, sourceFilter) {
        canonicalCachePlaylists(state.playlists)
            .filter { sourceFilter == null || it.source.name == sourceFilter }
            .sortedWith(compareBy<MusicPlaylist> { it.source.ordinal }.thenBy { it.id })
    }
    val cached = remember(state.cachedTracks, sourceFilter) {
        state.cachedTracks.filter { sourceFilter == null || it.source.name == sourceFilter }
            .sortedWith(compareBy<MusicTrack> { it.source.ordinal }.thenBy { it.id })
    }
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
                    val columns = qqLibraryColumns(maxWidth)
                    val wide = maxWidth >= 840.dp
                    val gutter = if (maxWidth >= 600.dp) 32.dp else 20.dp
                    LazyVerticalGrid(GridCells.Fixed(columns), state = grid, modifier = Modifier.widthIn(max = 1120.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = gutter, end = gutter, top = 20.dp, bottom = bottomInset + 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item("header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            SettingsHeader(selected?.title ?: if (selectedId == null) "缓存管理" else "缓存歌单", onBack = {
                                if (selection.active) selection.close() else onBack()
                            })
                        }
                        if (selectedId == null) {
                            if (wide) item("settings-wide", span = { GridItemSpan(maxLineSpan) }, contentType = "settings") {
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    Box(Modifier.weight(1f)) { CacheUsageSettings(state) { clearing = it } }
                                    Box(Modifier.weight(1f)) { CacheCapacitySettings(state, manager::stageCapacity) }
                                }
                            } else {
                                item("usage", span = { GridItemSpan(maxLineSpan) }, contentType = "settings") {
                                    CacheUsageSettings(state) { clearing = it }
                                }
                                item("capacity", span = { GridItemSpan(maxLineSpan) }, contentType = "settings") {
                                    CacheCapacitySettings(state, manager::stageCapacity)
                                }
                            }
                            item("tabs", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
                                LibrarySectionTabs(tab, listOf("按歌单", "缓存歌曲")) { tab = it; search.close() }
                            }
                            item("source-filter", span = { GridItemSpan(maxLineSpan) }, contentType = "controls") {
                                PlaybackSettingsMenuButton(MusicSource.entries.firstOrNull { it.name == sourceFilter }?.label ?: "全部音源", "筛选音源",
                                    listOf(PlaybackSettingsMenuOption("全部音源", sourceFilter == null) { sourceFilter = null }) +
                                        MusicSource.entries.map { provider -> PlaybackSettingsMenuOption(provider.label, sourceFilter == provider.name) { sourceFilter = provider.name } })
                            }
                            if (tab == 0) {
                                val primary = playlists.filter { it.isPrimaryCachePlaylist }
                                val others = playlists.filterNot { it.isPrimaryCachePlaylist }
                                    .matchingCachePlaylistQuery(search.query)
                                if (primary.isNotEmpty()) item("group:primary", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                                    Text("一级歌单", style = MaterialTheme.typography.titleLarge)
                                }
                                items(primary, key = { "playlist:${cachePlaylistKey(it)}" }, contentType = { "playlist" }) { playlist ->
                                    CachePlaylistCard(playlist, state, { onOpen(cachePlaylistKey(playlist)) },
                                        { manager.play(playlist, playback.playlist) })
                                }
                                // 标题和同行搜索框跟随页面滚动，不吸顶。
                                item("group:other", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                                    CachePlaylistSearchHeader(search)
                                }
                                items(others, key = { "playlist:${cachePlaylistKey(it)}" }, contentType = { "playlist" }) { playlist ->
                                    CachePlaylistCard(playlist, state, { onOpen(cachePlaylistKey(playlist)) },
                                        { manager.play(playlist, playback.playlist) })
                                }
                                if (others.isEmpty() && search.query.isNotBlank()) item("no-playlist-match", span = { GridItemSpan(maxLineSpan) }) {
                                    Text("未找到匹配的歌单")
                                }
                                if (playlists.isEmpty()) item("empty-playlists", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(if (!state.initialized) "" else if (sourceFilter == MusicSource.BILIBILI.name)
                                        "暂无收藏夹，请登录哔哩哔哩后查看。" else "暂无歌单，登录音源或创建本地歌单后可在这里管理。")
                                }
                            } else {
                                items(cached, key = { "song:${it.cacheIdentity()}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "song" }) { track ->
                                    CacheSongRow(track, state, onPlay, manager::cacheTrack, removeSong, currentTrackId, playing)
                                }
                                if (cached.isEmpty()) item("empty-cache", span = { GridItemSpan(maxLineSpan) }) {
                                    Text(if (state.initialized) "暂无缓存歌曲" else "")
                                }
                            }
                        } else if (selected != null) {
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
    CacheClearConfirmation(clearing, { clearing = null }, manager::clear)
    deleting?.let { playlist ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除${playlist.title}的缓存？") },
            text = { Text("只删除共用音频缓存，不删除歌单、收藏或本地音乐原文件。同曲在其他歌单中也会变为未缓存。") },
            confirmButton = { TextButton(onClick = { deleting = null; manager.remove(playlist) }) { Text("删除缓存") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
