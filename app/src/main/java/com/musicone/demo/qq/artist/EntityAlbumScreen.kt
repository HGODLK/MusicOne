package com.musicone.demo

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

/** 专辑详情沿用歌单内容、搜索和定位，原始曲序不做收藏时间排序。 */
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
            val session = PlatformPreferences(context).readSession(MusicSource.QQ)
            QqAlbumRepository().load(initial, session.credential, session.account?.hasVipAccess == true)
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.asUserMessage() }
        finally { loading = false }
    }
    val toolbar = rememberPlaylistToolbarOverlayState()
    val localTools = rememberPlaylistFloatingToolsOverlayState()
    val tools = LocalEntityFrame.current?.floatingTools ?: localTools
    val backdrop = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val favorites = LocalMusicFavorites.current
    val motion = LocalPlaylistMotion.current
    val activation = motion?.let { rememberPlaylistContentActivation(initial.id, it) }
    val tracksEntrance = rememberEntityDataEntrance(album.tracks.isNotEmpty())
    QqPlaylistSongMenuHost(album, created, bottomInset, {}, onChanged, favorites.toggle) {
        val frame = LocalEntityFrame.current
        val menu = LocalQqPlaylistSongMenu.current
        SideEffect { frame?.backAction = {
            when {
                menu?.expanded == true -> menu.back()
                tools.search?.expanded == true -> tools.search?.close()
                else -> toolbar.back()
            }
        } }
        Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }) {
            Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(backdrop, LocalQqPlaylistSongMenu.current?.expanded == true)) {
                CompositionLocalProvider(LocalPlaylistContentEntrance provides { activation?.entrance?.value ?: 1f }) {
                RecommendationScreen(album, state, bottomInset, loading, error, false, false, null, false,
                    PlaylistSort.ADDED_DESC, {}, onBack, {}, { onPlay(album.tracks, null) },
                    { onShuffle(album) }, { onPlay(album.tracks, it) }, favorites.toggle, toolbar, tools,
                    contentMounted = activation?.mounted != false, dataEntrance = { tracksEntrance.value })
                }
            }
            PlaylistToolbar(backdrop, bounds, toolbar::back)
            if (error != null) TextButton(onClick = { attempt++ }, Modifier.align(Alignment.Center)) { Text("重新加载专辑") }
        }
    }
}
