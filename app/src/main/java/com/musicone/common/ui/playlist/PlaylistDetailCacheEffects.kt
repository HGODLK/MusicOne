package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 只缓存账号关联的歌单：自己创建的、收藏的和“我喜欢”；推荐歌单、专辑、本地歌单和临时歌单不缓存。 */
internal fun playlistDetailCacheable(playlist: MusicPlaylist, created: List<MusicPlaylist>,
    collected: List<MusicPlaylist>): Boolean =
    playlist.source != MusicSource.LOCAL && !playlist.isManagedLibrary && !playlist.isSourceAlbum &&
        shouldCachePlaylistDetail(playlist) && (playlist.isOwned || playlist.isAccountFavorites() ||
        created.any { it.source == playlist.source && it.id == playlist.id } ||
        collected.any { it.source == playlist.source && it.id == playlist.id })

@Composable
internal fun rememberPlaylistDetailCache(enabled: Boolean): PlaylistDetailCache? {
    val context = LocalContext.current.applicationContext
    return remember(context, enabled) { if (enabled) PlaylistDetailCache(context) else null }
}

/**
 * 进入歌单：先把本地缓存交给界面，再拉第一页核对。
 * 第一页和缓存开头一致就沿用缓存，不再拉后续分页；对不上或没有缓存时后台拉全量，拉完一次性替换。
 * 离线或第一页失败时继续显示缓存。
 */
internal suspend fun PlaylistPageActions.firstWithCache(cache: PlaylistDetailCache?, original: MusicPlaylist,
    accountId: String?) {
    if (cache != null && entry.cached == null && entry.page == null) {
        entry.cached = withContext(Dispatchers.IO) { cache.read(original, accountId) } ?: original.completeTracksOrNull()
    }
    first()
    if (cache == null) return
    val page = entry.page ?: return
    if (page.next == null) return
    val cached = entry.cached
    if (cached != null && playlistCacheStillFresh(cached, page.playlist)) entry.adoptCached() else remaining()
}

/** 歌单完整加载后回写缓存；内容没变不重写。 */
@Composable
internal fun PlaylistDetailCacheSaveEffect(entry: PlaylistPagingEntry, cache: PlaylistDetailCache?, accountId: String?) {
    val complete = entry.page?.takeIf { it.next == null }?.playlist
    LaunchedEffect(entry, cache, complete) {
        if (cache == null || complete == null || !playlistCacheNeedsSave(entry.cached, complete)) return@LaunchedEffect
        withContext(Dispatchers.IO) { cache.save(complete, accountId) }
        entry.cached = complete
    }
}
