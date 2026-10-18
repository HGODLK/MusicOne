package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal fun ObserveLibraryPlaylist(navigation: RecommendationNavigation) {
    val ui = LocalMusicLibraryUi.current
    val current = navigation.playlist
    LaunchedEffect(current?.id) {
        val id = current?.id ?: return@LaunchedEffect
        if (ui.snapshot.subscriptions.none { it.playlistId == id }) return@LaunchedEffect
        try { refreshLibrarySubscription(ui.context, ui.store, id) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { ui.message = "订阅刷新失败，已保留原内容：${error.message.orEmpty()}" }
    }
    LaunchedEffect(ui.snapshot, current?.id) {
        if (current?.isManagedLibrary == true) {
            val updated = if (current.id == ALL_LOCAL_PLAYLIST_ID) ui.snapshot.allLocal else ui.snapshot.playlists.firstOrNull { it.id == current.id }
            if (updated != null && updated != current) navigation.update(updated)
            else if (updated == null) navigation.close()
        }
    }
}
