package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

/** 专辑详情沿用歌单内容、搜索、定位和排序，默认保持专辑原曲序。 */
@Composable
internal fun EntityAlbumScreen(initial: MusicPlaylist, state: MusicOneUiState, bottomInset: Dp,
    created: List<MusicPlaylist>, onBack: () -> Unit, onPlay: (List<MusicTrack>, MusicTrack?) -> Unit,
    onChanged: (MusicPlaylist) -> Unit, onShuffle: (MusicPlaylist) -> Unit) {
    val context = LocalContext.current
    var album by remember(initial.id) { mutableStateOf(initial) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(initial.id, attempt) {
        loading = true; error = null
        try { album = withContext(Dispatchers.IO) {
            PlatformCatalogRepository(context).playlistDetail(initial)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.asUserMessage() }
        finally { loading = false }
    }
    val sort = rememberPlaylistSortSelection(initial, initial.sourceAccountId, created)
    val displayed = remember(album, sort.value) { album.copy(tracks = sortedPlaylistTracks(album.tracks, sort.value)) }
    val toolbar = rememberPlaylistToolbarOverlayState()
    val localTools = rememberPlaylistFloatingToolsOverlayState()
    val tools = LocalEntityFrame.current?.floatingTools ?: localTools
    val backdrop = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val favorites = LocalMusicFavorites.current
    val motion = LocalPlaylistMotion.current
    val activation = motion?.let { rememberPlaylistContentActivation(initial.id, it) }
    val tracksEntrance = rememberEntityDataEntrance(album.tracks.isNotEmpty())
    MainSourcePlaylistSongMenuHost(album, created, bottomInset, {}, onChanged, favorites.toggle) {
        val frame = LocalEntityFrame.current
        val menu = LocalMainSourcePlaylistSongMenu.current
        SideEffect { frame?.backAction = {
            when {
                menu?.expanded == true -> menu.back()
                tools.search?.expanded == true -> tools.search?.close()
                else -> toolbar.back()
            }
        } }
        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, LocalMainSourcePlaylistSongMenu.current?.expanded == true)) {
                CompositionLocalProvider(LocalPlaylistContentEntrance provides { activation?.entrance?.value ?: 1f }) {
                RecommendationScreen(displayed, state, bottomInset, loading, error, false, false, null, false,
                    sort.value, sort.select, onBack, {}, { onPlay(displayed.tracks, null) },
                    { onShuffle(album) }, { onPlay(displayed.tracks, it) }, favorites.toggle, toolbar, tools,
                    contentMounted = activation?.mounted != false, dataEntrance = { tracksEntrance.value })
                }
            }
            PlaylistToolbar(backdrop, bounds, toolbar::back)
            if (error != null) TextButton(onClick = { attempt++ }, Modifier.align(Alignment.Center)) { Text("重新加载专辑") }
        }
    }
}
