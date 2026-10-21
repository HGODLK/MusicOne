package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalPlaylistPagination = staticCompositionLocalOf<PlaylistPageActions?> { null }

internal class PlaylistPageActions(
    val entry: PlaylistPagingEntry,
    private val controller: PlaylistPagingController,
    private val scope: CoroutineScope,
    private val playlist: () -> MusicPlaylist,
    private val sort: () -> PlaylistSort,
    private val playback: MusicPlaybackRuntime,
    private val preparePlayback: () -> Unit,
    private val isOnline: () -> Boolean,
    private val cachedTrackIds: suspend () -> Set<String>,
) {
    private var playJob: Job? = null
    private var completeJob: Job? = null

    suspend fun first() { if (isOnline()) attempt { controller.first(playlist()) } }

    suspend fun selectionTracks(): List<MusicTrack> {
        val full = controller.complete(playlist())
        return if (full.isQqSearchAlbum) full.tracks else sortedPlaylistTracks(full.tracks, sort())
    }

    fun loadedSelectionTracks(): List<MusicTrack>? = entry.page?.playlist?.let {
        if (it.isQqSearchAlbum) it.tracks else sortedPlaylistTracks(it.tracks, sort())
    }

    fun more() {
        if (entry.loading || !isOnline()) return
        scope.launch { attempt { controller.more(playlist()) } }
    }

    fun remaining() {
        if (completeJob?.isActive == true || !isOnline()) return
        completeJob = scope.launch { attempt { controller.complete(playlist()) } }
    }

    fun play(track: MusicTrack? = null, shuffle: Boolean = false) {
        playJob?.cancel()
        playJob = scope.launch {
            attempt {
                val online = isOnline()
                val full = playlistForPlayback(entry.page?.playlist ?: playlist(), online, cachedTrackIds) {
                    completePlaylistForSelectedTrack({
                        controller.checkCurrent(playlist(), entry)
                        entry.page?.playlist ?: playlist()
                    }, track) {
                        controller.complete(playlist())
                    }
                }
                val sorted = if (full.isQqSearchAlbum) full else
                    full.copy(tracks = sortedPlaylistTracks(full.tracks, sort()))
                val selected = if (online) track else track?.let { wanted ->
                    sorted.tracks.firstOrNull { it.id == wanted.id } ?: error("这首歌曲尚未完整缓存")
                }
                preparePlayback()
                if (selected != null) playback.playPlaylistTrack(sorted, selected)
                else playback.playPlaylist(sorted, shuffle = shuffle)
            }
        }
    }

    private suspend fun attempt(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { entry.error = error.asUserMessage() }
    }
}

@Composable
internal fun rememberPlaylistPageActions(
    controller: PlaylistPagingController, entry: PlaylistPagingEntry, playlist: MusicPlaylist,
    sort: PlaylistSort, playback: MusicPlaybackRuntime, preparePlayback: () -> Unit,
): PlaylistPageActions {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val currentPlaylist = rememberUpdatedState(playlist)
    val currentSort = rememberUpdatedState(sort)
    val prepare = rememberUpdatedState(preparePlayback)
    return remember(controller, entry, playback, context) {
        PlaylistPageActions(entry, controller, scope, { currentPlaylist.value }, { currentSort.value }, playback,
            { prepare.value() }, { context.hasMusicNetwork() },
            { withContext(Dispatchers.IO) { OfflineMusicStore(context).availableTrackIds() } })
    }
}

/** 复用歌手页的末尾八项预取规则；搜索和全局排序按页补齐，不创建全量行节点。 */
@Composable
internal fun ObservePlaylistPagination(scroll: LazyListState, enabled: Boolean, complete: Boolean) {
    val actions = LocalPlaylistPagination.current ?: return
    LaunchedEffect(actions, scroll, enabled, complete) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val layout = scroll.layoutInfo
            val nearEnd = layout.visibleItemsInfo.lastOrNull()?.index?.let {
                it >= layout.totalItemsCount - 9
            } == true
            actions.entry.page?.next?.offset?.takeIf {
                !actions.entry.loading && actions.entry.error == null && (nearEnd || complete)
            }
        }.distinctUntilChanged().collect { offset ->
            if (offset != null) {
                if (complete) actions.remaining() else actions.more()
            }
        }
    }
}

@Composable
internal fun PlaylistPaginationFooter() {
    val actions = LocalPlaylistPagination.current ?: return
    ArtistLoadMore(actions.entry.loading, actions.entry.error, actions.entry.page?.next != null, actions::more)
}
