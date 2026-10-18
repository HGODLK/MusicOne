package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal val LocalPlaylistPagination = staticCompositionLocalOf<PlaylistPageActions?> { null }

internal class PlaylistPageActions(
    val entry: PlaylistPagingEntry,
    private val controller: PlaylistPagingController,
    private val scope: CoroutineScope,
    private val playlist: () -> MusicPlaylist,
    private val sort: () -> PlaylistSort,
    private val playback: MusicPlaybackRuntime,
    private val preparePlayback: () -> Unit,
) {
    private var playJob: Job? = null
    private var completeJob: Job? = null

    suspend fun first() { attempt { controller.first(playlist()) } }

    suspend fun selectionTracks(): List<MusicTrack> {
        val full = controller.complete(playlist())
        return if (full.isQqSearchAlbum) full.tracks else sortedPlaylistTracks(full.tracks, sort())
    }

    fun loadedSelectionTracks(): List<MusicTrack>? = entry.page?.playlist?.let {
        if (it.isQqSearchAlbum) it.tracks else sortedPlaylistTracks(it.tracks, sort())
    }

    fun more() {
        if (entry.loading) return
        scope.launch { attempt { controller.more(playlist()) } }
    }

    fun remaining() {
        if (completeJob?.isActive == true) return
        completeJob = scope.launch { attempt { controller.complete(playlist()) } }
    }

    fun play(track: MusicTrack? = null, shuffle: Boolean = false) {
        playJob?.cancel()
        playJob = scope.launch {
            attempt {
                val full = controller.complete(playlist())
                val sorted = if (full.isQqSearchAlbum) full else
                    full.copy(tracks = sortedPlaylistTracks(full.tracks, sort()))
                preparePlayback()
                if (track != null) playback.playPlaylistTrack(sorted, track)
                else playback.playPlaylist(sorted, shuffle = shuffle)
            }
        }
    }

    private suspend fun attempt(action: suspend () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 分页状态已保留错误和游标，交给列表尾部重试。 */ }
    }
}

@Composable
internal fun rememberPlaylistPageActions(
    controller: PlaylistPagingController, entry: PlaylistPagingEntry, playlist: MusicPlaylist,
    sort: PlaylistSort, playback: MusicPlaybackRuntime, preparePlayback: () -> Unit,
): PlaylistPageActions {
    val scope = rememberCoroutineScope()
    val currentPlaylist = rememberUpdatedState(playlist)
    val currentSort = rememberUpdatedState(sort)
    val prepare = rememberUpdatedState(preparePlayback)
    return remember(controller, entry, playback) {
        PlaylistPageActions(entry, controller, scope, { currentPlaylist.value }, { currentSort.value }, playback,
            { prepare.value() })
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
