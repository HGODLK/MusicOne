package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.distinctUntilChanged

/** 距离末尾八项时预取，失败后保留手动重试，避免自动重试风暴。 */
@Composable
internal fun ArtistPagination(data: ArtistPageState, songs: LazyListState, albums: LazyListState,
    tab: Int, enabled: Boolean, searchingSongs: Boolean) {
    LaunchedEffect(data, songs, albums, tab, enabled, searchingSongs) {
        if (!enabled) return@LaunchedEffect
        val scroll = if (tab == 0) songs else albums
        snapshotFlow {
            val layout = scroll.layoutInfo
            val nearEnd = layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 9 } == true
            val loading = when {
                tab == 0 && searchingSongs -> data.searchLoading
                tab == 0 -> data.songLoading
                else -> data.albumLoading
            }
            val next = when {
                tab == 0 && searchingSongs -> data.searchNext
                tab == 0 -> data.songNext
                else -> data.albumNext
            }
            Triple(nearEnd, loading, next)
        }.distinctUntilChanged().collect { (nearEnd, loading, next) ->
            if (nearEnd && !loading && next != null) {
                if (tab == 0 && searchingSongs && data.searchError == null) data.loadSearchSongs()
                else if (tab == 0 && data.songError == null) data.loadSongs()
                if (tab == 1 && data.albumError == null) data.loadAlbums()
            }
        }
    }
}
