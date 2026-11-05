package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 会话临时视图复用完整歌单详情、搜索和工具栏，不创建持久歌单。 */
@Composable
internal fun AiPlaylistDetailRoute(playlist: MusicPlaylist, controller: AiAssistantController, bottomInset: Dp,
    motion: PageMotion, onBack: () -> Unit) {
    val playback = controller.playbackState?.collectAsStateWithLifecycle()?.value ?: MusicOneUiState()
    val favorites = LocalMusicFavorites.current
    val activation = rememberPlaylistContentActivation(playlist.id, motion)
    val entrance = remember(activation) { { activation.entrance.value } }
    var sort by remember(playlist.id) { mutableStateOf(PlaylistSort.ADDED_DESC) }
    val displayed = remember(playlist, sort) { playlist.copy(tracks = sortedPlaylistTracks(playlist.tracks, sort)) }
    val toolbar = rememberPlaylistToolbarOverlayState()
    val tools = rememberPlaylistFloatingToolsOverlayState()
    val backdrop = rememberGraphicsLayer()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val composerBackdrop = LocalAiComposerBackdrop.current
    DisposableEffect(composerBackdrop) { onDispose { composerBackdrop?.artworkBounds = Rect.Zero } }
    CompositionLocalProvider(LocalPlaylistMotion provides motion,
        LocalPlaylistContentEntrance provides entrance,
        LocalPlaylistLoadedContent provides true,
        LocalMiniPlayerGlassSampling provides rememberMiniPlayerGlassSampling(backdrop)) {
        PlaylistMotionHost(motion, playlist) {
            Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() }
                    .drawWithContent { backdrop.record { this@drawWithContent.drawContent() }; drawLayer(backdrop) }) {
                    RecommendationScreen(displayed, playback, bottomInset, loading = false, loadMessage = null,
                        saved = false, updatingCollection = false, collectionMessage = null, showCollection = false,
                        sort = sort, onSort = { sort = it }, onBack = onBack, onToggleSaved = {},
                        onPlay = { controller.playPlaylist(displayed) }, onShuffle = { controller.playPlaylist(displayed, true) },
                        onTrackClick = controller::playTrack, onFavorite = favorites.toggle,
                        toolbarState = toolbar, floatingToolsState = tools,
                        contentMounted = activation.mounted)
                }
                PlaylistToolbarOverlay(toolbar, motion, backdrop, bounds, Modifier.align(Alignment.TopCenter))
                PlaylistFloatingToolsOverlay(tools, motion, backdrop, bounds, bottomInset, Modifier.align(Alignment.BottomEnd))
            }
            val cover = displayed.tracks.firstOrNull()
            FlyingPlaylistArtwork(motion, displayed.artworkUrl, cover?.artworkStart ?: displayed.artworkStart,
                cover?.artworkEnd ?: displayed.artworkEnd, cover?.artworkMark ?: displayed.artworkMark,
                modifier = Modifier.aiComposerArtworkBackdrop(composerBackdrop))
            }
        }
    }
}
