package com.musicone

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
        motion.beforeShownHandoff = ::settlePlaylistCoverHandoff
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
        dragAnimation = PlaylistGestureSettlementAnimation,
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val context = LocalContext.current
    val sourceContent = remember(context) { com.musicone.sourceintegration.SourceApplicationHost.get(context).contentUi }
    val cards = remember {
        PlaylistCardTransition(scope) {
            // 包内公共卡片先交付歌单请求，再由原宿主 effect 打开，不能提前恢复封面。
            selectedPlaylist.value != null || sourceContent.request != null ||
                sourceContent.pages.active is com.musicone.source.sdk.SourcePageRequest.Collection
        }
    }
    motion.coverKey = cards.activeSourceKey ?: selectedPlaylist.value?.id.orEmpty()
    return remember(selectedPlaylist, motion) { RecommendationNavigation(selectedPlaylist, motion, cards) }
}

@Composable
internal fun RecommendationDestination(
    navigation: RecommendationNavigation,
    state: MusicOneUiState,
    viewModel: MusicPlaybackRuntime,
    bottomInset: Dp,
    loadingPlaylistId: String?,
    loadMessage: String?,
    mainLibrary: MainSourceLibraryUiState,
    accountId: String?,
    onToggleSourceCollection: (MusicPlaylist) -> Unit,
    onReturnFromSourcePlaylist: (MusicPlaylist, Boolean) -> Unit,
    toolbarState: PlaylistToolbarOverlayState,
    floatingToolsState: PlaylistFloatingToolsOverlayState,
    sourcePlaylistBackgroundRevision: Int,
    onChooseSourcePlaylistBackground: (MusicPlaylist) -> Unit,
    onRestoreSourcePlaylistBackground: (MusicPlaylist) -> Unit,
) {
    val context = LocalContext.current
    val favorites = LocalMusicFavorites.current
    val libraryPageState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val catalog: MusicCatalogViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val savedPlaylistIds by viewModel.playlistLibrary.ids.collectAsStateWithLifecycle()
    ObserveLibraryPlaylist(navigation)
    navigation.playlist?.let { original ->
        val paging = catalog.playlists.entry(original)
        val playlist = paging.page?.playlist ?: original
        val visuals = com.musicone.sourceintegration.SourcePackageController.get(context).running(playlist.source.id)?.entry?.visuals
        val visualRoute = com.musicone.source.sdk.SourceRoute("playlist", mapOf("id" to playlist.id, "owned" to playlist.isOwned.toString()))
        val visualFlow = remember(visuals, visualRoute) { visuals?.observe(visualRoute) }
        val visualState = visualFlow?.collectAsStateWithLifecycle()?.value
        val playlistVisual = if (visuals == null) null else rememberSourceBackdropVisual(
            customBackgroundUri = visualState?.backgroundUri ?: playlist.artworkUrl.takeIf { !playlist.isOwned },
            customBackgroundRevision = sourcePlaylistBackgroundRevision + (visualState?.revision ?: 0).toInt(),
            foregroundArgb = visualState?.foregroundArgb,
        )
        val sort = rememberPlaylistSortSelection(playlist, accountId, mainLibrary.createdPlaylists)
        val pageActions = rememberPlaylistPageActions(catalog.playlists, paging, playlist, sort.value, viewModel) {
            navigation.cards.preparePlayback(navigation.motion.coverKey)
        }
        androidx.compose.runtime.LaunchedEffect(pageActions) { pageActions.first() }
        androidx.compose.runtime.LaunchedEffect(paging.page) { paging.page?.playlist?.let(navigation::update) }
        androidx.compose.runtime.DisposableEffect(paging) {
            onDispose { catalog.playlists.release(playlist.id, paging) }
        }
        val sorted = remember(playlist, sort.value) {
            playlist.copy(tracks = sortedPlaylistTracks(playlist.tracks, sort.value))
        }
        val contentActivation = rememberPlaylistContentActivation(playlist.id, navigation.motion)
        val dataEntrance = androidx.compose.runtime.key(playlist.id) {
            rememberEntityDataEntrance(paging.page != null || playlist.tracks.isNotEmpty())
        }
        val sourceFavorites = com.musicone.sourceintegration.SourcePackageController.get(context)
            .running(playlist.source.id)?.entry?.favorites
        val supportsCollections = sourceFavorites?.supportsCollections == true
        val refreshAfterAnyReturn = (com.musicone.sourceintegration.SourcePackageController.get(context)
            .running(playlist.source.id)?.entry?.collections as? com.musicone.source.sdk.SourceCollectionReturnBehavior)
            ?.refreshLibraryAfterAnyReturn == true
        val showCollection = supportsCollections && playlist.canCollectWith(sourceFavorites, accountId) && !playlist.isManagedLibrary && !playlist.isSourceAlbum &&
            !playlist.isOwned && mainLibrary.createdPlaylists.none { it.source == playlist.source && it.id == playlist.id }
        val deferFavoriteRemovals = playlist.isAccountFavorites()
        androidx.compose.runtime.DisposableEffect(playlist.id, deferFavoriteRemovals) {
            onDispose { if (deferFavoriteRemovals) favorites.flushDeferredRemovals {} }
        }
        val closePlaylist = {
            navigation.close {
                if (shouldSyncSourceLibraryAfterPlaylistReturn(playlist, refreshAfterAnyReturn)) {
                    if (deferFavoriteRemovals || refreshAfterAnyReturn) {
                        val contentChanged = favorites.currentState().deferredRemovalIds.isNotEmpty()
                        favorites.flushDeferredRemovals { onReturnFromSourcePlaylist(playlist, contentChanged) }
                    } else {
                        onReturnFromSourcePlaylist(playlist, false)
                    }
                }
            }
        }
        MainSourcePlaylistSongMenuHost(
            playlist = playlist,
            createdPlaylists = mainLibrary.createdPlaylists,
            bottomInset = bottomInset,
            onRemoved = { removed -> navigation.playlist?.let { current ->
                paging.remove(removed.id)
                navigation.update(current.copy(tracks = current.tracks.filterNot { it.id == removed.id },
                    count = (current.count - 1).coerceAtLeast(0)))
            } },
            onChanged = { changed -> onReturnFromSourcePlaylist(changed, true) },
            onFavorite = if (deferFavoriteRemovals) favorites.toggleDeferredRemoval else favorites.toggle,
        ) {
        PlaylistMotionHost(navigation.motion, playlist, playlistVisual) {
            SourceBackdropForeground(playlistVisual) {
            val entranceProgress = remember(contentActivation) { { contentActivation.entrance.value } }
            androidx.compose.runtime.CompositionLocalProvider(
                LocalPlaylistContentEntrance provides entranceProgress,
                LocalPlaylistLoadedContent provides true,
                LocalPlaylistPagination provides pageActions,
            ) {
                libraryPageState.SaveableStateProvider(playlist.id) {
                RecommendationScreen(
                    playlist = sorted,
                    state = state,
                    bottomInset = bottomInset,
                    loading = paging.loading || loadingPlaylistId == playlist.id,
                    loadMessage = paging.error ?: loadMessage,
                    onBack = closePlaylist,
                    saved = if (supportsCollections) mainLibrary.collectedPlaylists.any { sameSourceCollection(it, playlist, sourceFavorites, accountId) } else playlist.id in savedPlaylistIds,
                    updatingCollection = playlist.id in mainLibrary.updatingCollections,
                    collectionMessage = mainLibrary.collectionMessage,
                    showCollection = showCollection,
                    sort = sort.value,
                    onSort = sort.select,
                    onToggleSaved = { if (supportsCollections) onToggleSourceCollection(playlist) else viewModel.playlistLibrary.toggle(playlist) },
                    onPlay = { pageActions.play(shuffle = false) },
                    onShuffle = { pageActions.play(shuffle = true) },
                    onTrackClick = { pageActions.play(track = it) },
                    onFavorite = if (deferFavoriteRemovals) favorites.toggleDeferredRemoval else favorites.toggle,
                    toolbarState = toolbarState,
                    floatingToolsState = floatingToolsState,
                    contentMounted = contentActivation.mounted,
                    dataEntrance = { dataEntrance.value },
                    usePageBackground = playlistVisual != null,
                    hasCustomBackground = visualState?.customBackground == true,
                    onChooseCustomBackground = if (visuals?.supportsCustomBackground == true && playlist.isOwned) {
                        { onChooseSourcePlaylistBackground(playlist) }
                    } else null,
                    onRestoreInheritedBackground = if (visuals?.supportsCustomBackground == true && playlist.isOwned) {
                        { onRestoreSourcePlaylistBackground(playlist) }
                    } else null,
                )
                }
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

internal fun shouldSyncSourceLibraryAfterPlaylistReturn(playlist: MusicPlaylist,
    refreshAfterAnyReturn: Boolean = false): Boolean =
    playlist.source != MusicSource.LOCAL && (refreshAfterAnyReturn || !playlist.isSourceAlbum)
