package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal class RecommendationNavigation(private val selectedPlaylist: MutableState<MusicPlaylist?>, val motion: PageMotion, val cards: PlaylistCardTransition) {
    val playlist: MusicPlaylist?
        get() = selectedPlaylist.value

    private var afterClosed: (() -> Unit)? = null

    init {
        motion.onHidden = {
            selectedPlaylist.value = null
            cards.restore()
            afterClosed?.also { callback ->
                afterClosed = null
                callback()
            }
        }
    }

    fun open(playlist: MusicPlaylist) {
        if (motion.mounted && selectedPlaylist.value?.id != playlist.id) return
        selectedPlaylist.value = playlist
        motion.coverKey = cards.activeSourceKey ?: playlist.id
        motion.request(true)
    }
    fun update(playlist: MusicPlaylist) {
        if (selectedPlaylist.value?.id == playlist.id) selectedPlaylist.value = playlist
    }
    fun close(onClosed: () -> Unit = {}) {
        afterClosed = onClosed
        motion.request(false)
    }
}

@Composable
internal fun rememberRecommendationNavigation(): RecommendationNavigation {
    val selectedPlaylist = remember { mutableStateOf<MusicPlaylist?>(null) }
    val motion = rememberPageMotion(
        initialOpen = selectedPlaylist.value != null,
        exitAnimation = PlaylistReturnAnimation,
        enterAnimation = PlaylistEnterAnimation,
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cards = remember { PlaylistCardTransition(scope) }
    motion.coverKey = cards.activeSourceKey ?: selectedPlaylist.value?.id.orEmpty()
    return remember(selectedPlaylist, motion) { RecommendationNavigation(selectedPlaylist, motion, cards) }
}

@Composable
internal fun RecommendationDestination(
    navigation: RecommendationNavigation,
    state: MusicOneUiState,
    viewModel: MusicOneViewModel,
    bottomInset: Dp,
    loadingPlaylistId: String?,
    loadMessage: String?,
    qqLibrary: QqLibraryUiState,
    qqAccountId: String?,
    onToggleQqCollection: (MusicPlaylist) -> Unit,
    onReturnFromQqPlaylist: (MusicPlaylist, Boolean) -> Unit,
    toolbarState: PlaylistToolbarOverlayState,
    floatingToolsState: PlaylistFloatingToolsOverlayState,
    neteasePlaylistBackgroundRevision: Int,
    onChooseNeteasePlaylistBackground: (MusicPlaylist) -> Unit,
    onRestoreNeteasePlaylistBackground: (MusicPlaylist) -> Unit,
) {
    val context = LocalContext.current
    val favorites = LocalMusicFavorites.current
    val inheritedNeteaseVisual = LocalNeteaseGlobalVisual.current
    val savedPlaylistIds by viewModel.playlistLibrary.ids.collectAsStateWithLifecycle()
    navigation.playlist?.let { playlist ->
        val customPlaylistBackground = remember(playlist.id, neteasePlaylistBackgroundRevision) {
            if (playlist.source == MusicSource.NETEASE) {
                NeteaseProfileBackgroundStore.playlistUri(context, playlist.id)
            } else null
        }
        val generatedVisualSource = when {
            playlist.source != MusicSource.NETEASE -> null
            customPlaylistBackground != null -> customPlaylistBackground
            playlist.isOwned -> null
            else -> playlist.artworkUrl
        }
        val generatedVisual = rememberNeteaseProfileVisual(
            customBackgroundUri = generatedVisualSource,
            customBackgroundRevision = neteasePlaylistBackgroundRevision,
        )
        val playlistVisual = when {
            playlist.source != MusicSource.NETEASE -> null
            customPlaylistBackground != null -> generatedVisual
            playlist.isOwned -> inheritedNeteaseVisual ?: generatedVisual
            else -> generatedVisual
        }
        val sort = rememberPlaylistSortSelection(playlist, qqAccountId, qqLibrary.createdPlaylists)
        val sorted = remember(playlist, sort.value) {
            if (playlist.isQqSearchAlbum) playlist else playlist.copy(tracks = sortedPlaylistTracks(playlist.tracks, sort.value))
        }
        val contentActivation = rememberPlaylistContentActivation(playlist.id, navigation.motion)
        val showCollection = !playlist.isQqSearchAlbum && (playlist.source != MusicSource.QQ || !playlist.isOwnedQqPlaylist(qqLibrary.createdPlaylists))
        val deferFavoriteRemovals = playlist.source == MusicSource.QQ && playlist.isQqFavoritesShortcut()
        androidx.compose.runtime.DisposableEffect(playlist.id, deferFavoriteRemovals) {
            onDispose { if (deferFavoriteRemovals) favorites.flushDeferredRemovals {} }
        }
        val closePlaylist = {
            navigation.close {
                if (shouldSyncQqLibraryAfterPlaylistReturn(playlist)) {
                    if (deferFavoriteRemovals) {
                        val contentChanged = favorites.state.deferredRemovalIds.isNotEmpty()
                        favorites.flushDeferredRemovals { onReturnFromQqPlaylist(playlist, contentChanged) }
                    } else {
                        onReturnFromQqPlaylist(playlist, false)
                    }
                }
            }
        }
        QqPlaylistSongMenuHost(
            playlist = playlist,
            createdPlaylists = qqLibrary.createdPlaylists,
            bottomInset = bottomInset,
            onRemoved = { removed -> navigation.playlist?.let { current ->
                navigation.update(current.copy(tracks = current.tracks.filterNot { it.id == removed.id },
                    count = (current.count - 1).coerceAtLeast(0)))
            } },
            onChanged = { changed -> onReturnFromQqPlaylist(changed, true) },
            onFavorite = if (deferFavoriteRemovals) favorites.toggleDeferredRemoval else favorites.toggle,
        ) {
        PlaylistMotionHost(navigation.motion, playlist, playlistVisual) {
            NeteaseAdaptiveForeground(playlistVisual) {
            val entranceProgress = remember(contentActivation) { { contentActivation.entrance.value } }
            androidx.compose.runtime.CompositionLocalProvider(LocalPlaylistContentEntrance provides entranceProgress) {
                RecommendationScreen(
                    playlist = sorted,
                    state = state,
                    bottomInset = bottomInset,
                    loading = loadingPlaylistId == playlist.id,
                    loadMessage = loadMessage,
                    onBack = closePlaylist,
                    saved = if (playlist.source == MusicSource.QQ) qqLibrary.collectedPlaylists.any { it.id == playlist.id } else playlist.id in savedPlaylistIds,
                    updatingCollection = playlist.id in qqLibrary.updatingCollections,
                    collectionMessage = qqLibrary.collectionMessage,
                    showCollection = showCollection,
                    sort = sort.value,
                    onSort = sort.select,
                    onToggleSaved = { if (playlist.source == MusicSource.QQ) onToggleQqCollection(playlist) else viewModel.playlistLibrary.toggle(playlist) },
                    onPlay = { navigation.cards.preparePlayback(navigation.motion.coverKey); viewModel.playPlaylist(sorted) },
                    onShuffle = { navigation.cards.preparePlayback(navigation.motion.coverKey); viewModel.playPlaylist(sorted, shuffle = true) },
                    onTrackClick = { navigation.cards.preparePlayback(navigation.motion.coverKey); viewModel.playTrackNext(it) },
                    onFavorite = if (deferFavoriteRemovals) favorites.toggleDeferredRemoval else favorites.toggle,
                    toolbarState = toolbarState,
                    floatingToolsState = floatingToolsState,
                    contentMounted = contentActivation.mounted,
                    usePageBackground = playlistVisual != null,
                    hasCustomBackground = customPlaylistBackground != null,
                    onChooseCustomBackground = if (playlist.source == MusicSource.NETEASE && playlist.isOwned) {
                        { onChooseNeteasePlaylistBackground(playlist) }
                    } else null,
                    onRestoreInheritedBackground = if (playlist.source == MusicSource.NETEASE && playlist.isOwned) {
                        { onRestoreNeteasePlaylistBackground(playlist) }
                    } else null,
                )
            }
            if (playlistVisual == null) {
                val cover = sorted.tracks.firstOrNull()
                FlyingPlaylistArtwork(
                    motion = navigation.motion,
                    imageUrl = sorted.artworkUrl,
                    start = cover?.artworkStart ?: sorted.artworkStart,
                    end = cover?.artworkEnd ?: sorted.artworkEnd,
                    mark = cover?.artworkMark ?: sorted.artworkMark,
                )
            }
            }
        }
        }
    }
}

internal fun shouldSyncQqLibraryAfterPlaylistReturn(playlist: MusicPlaylist): Boolean =
    playlist.source == MusicSource.QQ && !playlist.isQqSearchAlbum
