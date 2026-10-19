package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 上区设置和一级歌单、下区其他歌单各自滚动；分组标题与搜索不属于任一滚动容器。 */
@Composable
internal fun CacheManagementHome(state: MusicCacheState, manager: MusicCacheManager,
    onBack: () -> Unit, onOpen: (String) -> Unit, onPlay: (MusicTrack) -> Unit,
    onPlayPlaylist: (MusicPlaylist) -> Unit, onRemove: (MusicTrack) -> Unit,
    onClear: (CacheClearMode) -> Unit, bottomInset: Dp, currentTrackId: String?, playing: Boolean) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    val search = rememberPlaylistSearchState("cache-playlists")
    if (search.expanded) BackHandler { search.close() }
    val playlists = remember(state.playlists, source) {
        canonicalCachePlaylists(state.playlists).filter { source == null || it.source.name == source }
            .sortedWith(compareBy<MusicPlaylist> { it.source.ordinal }.thenBy { it.id })
    }
    val primary = remember(playlists) { playlists.filter { it.isPrimaryCachePlaylist } }
    val others = remember(playlists, search.query) {
        playlists.filterNot { it.isPrimaryCachePlaylist }.matchingCachePlaylistQuery(search.query)
    }
    val cached = remember(state.cachedTracks, source) {
        state.cachedTracks.filter { source == null || it.source.name == source }
            .sortedWith(compareBy<MusicTrack> { it.source.ordinal }.thenBy { it.id })
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val columns = qqLibraryColumns(maxWidth)
        val wide = maxWidth >= 840.dp
        val gutter = if (maxWidth >= 600.dp) 32.dp else 20.dp
        Column(Modifier.widthIn(max = 1120.dp).fillMaxSize().padding(bottom = bottomInset)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp)) { SettingsHeader("缓存管理", onBack) }
            LazyVerticalGrid(GridCells.Fixed(columns), state = rememberLazyGridState(),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = gutter, end = gutter, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide) item("settings-wide", span = { GridItemSpan(maxLineSpan) }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Box(Modifier.weight(1f)) { CacheUsageSettings(state, onClear) }
                        Box(Modifier.weight(1f)) { CacheCapacitySettings(state, manager::stageCapacity) }
                    }
                } else {
                    item("usage", span = { GridItemSpan(maxLineSpan) }) { CacheUsageSettings(state, onClear) }
                    item("capacity", span = { GridItemSpan(maxLineSpan) }) { CacheCapacitySettings(state, manager::stageCapacity) }
                }
                item("tabs", span = { GridItemSpan(maxLineSpan) }) {
                    LibrarySectionTabs(tab, listOf("按歌单", "缓存歌曲")) { tab = it; search.close() }
                }
                item("source", span = { GridItemSpan(maxLineSpan) }) {
                    PlaybackSettingsMenuButton(MusicSource.entries.firstOrNull { it.name == source }?.label ?: "全部音源", "筛选音源",
                        listOf(PlaybackSettingsMenuOption("全部音源", source == null) { source = null }) + MusicSource.entries.map { provider ->
                            PlaybackSettingsMenuOption(provider.label, source == provider.name) { source = provider.name }
                        })
                }
                if (tab == 0 && primary.isNotEmpty()) {
                    item("primary-heading", span = { GridItemSpan(maxLineSpan) }) { Text("一级歌单", style = MaterialTheme.typography.titleLarge) }
                    items(primary, key = { cachePlaylistKey(it) }) { playlist ->
                        CachePlaylistCard(playlist, state, { onOpen(cachePlaylistKey(playlist)) }, { manager.play(playlist, onPlayPlaylist) })
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
                if (tab == 0) CachePlaylistSearchHeader(search)
                else Text("缓存歌曲", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
            }
            LazyVerticalGrid(GridCells.Fixed(columns), state = rememberLazyGridState(),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (tab == 0) {
                    items(others, key = { cachePlaylistKey(it) }) { playlist ->
                        CachePlaylistCard(playlist, state, { onOpen(cachePlaylistKey(playlist)) }, { manager.play(playlist, onPlayPlaylist) })
                    }
                    if (others.isEmpty()) item("empty-playlists", span = { GridItemSpan(maxLineSpan) }) {
                        Text(when {
                            !state.initialized -> ""
                            search.query.isNotBlank() -> "未找到匹配的歌单"
                            source == MusicSource.BILIBILI.name -> "暂无收藏夹，请登录哔哩哔哩后查看。"
                            else -> "暂无其他歌单"
                        })
                    }
                } else {
                    items(cached, key = { it.cacheIdentity() }, span = { GridItemSpan(maxLineSpan) }) { track ->
                        CacheSongRow(track, state, onPlay, manager::cacheTrack, onRemove, currentTrackId, playing)
                    }
                    if (cached.isEmpty()) item("empty-cache", span = { GridItemSpan(maxLineSpan) }) { Text(if (state.initialized) "暂无缓存歌曲" else "") }
                }
                state.message?.let { message -> item("message", span = { GridItemSpan(maxLineSpan) }) {
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
            }
        }
    }
}
