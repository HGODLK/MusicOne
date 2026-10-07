package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal val PlaylistPageColor: Color
    @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.background

@Composable
internal fun RecommendationScreen(
    playlist: MusicPlaylist,
    state: MusicOneUiState,
    bottomInset: Dp,
    loading: Boolean,
    loadMessage: String?,
    saved: Boolean,
    updatingCollection: Boolean,
    collectionMessage: String?,
    showCollection: Boolean,
    sort: PlaylistSort,
    onSort: (PlaylistSort) -> Unit,
    onBack: () -> Unit,
    onToggleSaved: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onTrackClick: (MusicTrack) -> Unit,
    onFavorite: (MusicTrack) -> Unit,
    toolbarState: PlaylistToolbarOverlayState,
    floatingToolsState: PlaylistFloatingToolsOverlayState,
    contentMounted: Boolean = true,
    dataEntrance: () -> Float = { 1f },
    usePageBackground: Boolean = false,
    hasCustomBackground: Boolean = false,
    onChooseCustomBackground: (() -> Unit)? = null,
    onRestoreInheritedBackground: (() -> Unit)? = null,
) {
    val favoriteIds = LocalMusicFavorites.current.state.ids
    val menu = LocalQqPlaylistSongMenu.current
    val menuAwareBack = { if (menu?.expanded == true) menu.back() else onBack() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = usesTabletLandscape(maxWidth, maxHeight)
        val tablet = playlist.source == MusicSource.QQ && maxWidth >= 600.dp
        val density = LocalDensity.current
        val coverHeight = if (usePageBackground) 112.dp else maxWidth
        val coverModifier = Modifier.fillMaxWidth().then(
            if (usePageBackground) Modifier.height(if (wide) 136.dp else 112.dp)
            else Modifier.aspectRatio(1f),
        )
        val motion = LocalPlaylistMotion.current
        key(playlist.id) {
            val search = rememberPlaylistSearchState(playlist.id)
            val searchCoverScrollOffset = with(density) {
                playlistSearchCoverScrollOffset(
                    if (tablet && search.coverHeightPx > 0) search.coverHeightPx else coverHeight.roundToPx(),
                    56.dp.roundToPx(),
                )
            }
            val informationScroll = rememberLazyListState()
            val tracksScroll = rememberLazyListState()
            val pull = LocalPlaylistPull.current
            val revealShape = remember(motion, pull) {
                if (motion != null && pull != null) PlaylistRevealShape(motion, pull) else null
            }
            val gestureModifier = Modifier.playlistPullGesture(
                if (wide) tracksScroll else informationScroll,
                motion.takeIf { menu?.expanded != true && LocalEntityActive.current },
                pull,
                menuAwareBack,
                requireScrollAtTop = !wide,
            )
            val pageMotionModifier = Modifier.fillMaxSize().graphicsLayer {
                alpha = playlistContentAlpha(motion?.value ?: 1f)
                if (revealShape != null) {
                    shape = revealShape
                    clip = true
                }
            }
                .then(if (wide) Modifier else gestureModifier)
            val scope = rememberCoroutineScope()
            val searchMotionActive = rememberPlaylistSearchMotionActive(search.expanded)
            val visibleTracks = remember(playlist.tracks, search.query) { playlist.tracks.matchingPlaylistQuery(search.query) }
            val visiblePlaylist = remember(playlist, visibleTracks) { playlist.copy(tracks = visibleTracks) }
            val searchResults = rememberPlaylistSearchResults(visibleTracks, searchMotionActive)
            val imeBottomInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            val contentBottomPadding = animateDpAsState(
                playlistSearchResultsBottomPadding(bottomInset, imeBottomInset, search.expanded),
                musicMotion(240),
                label = "歌单搜索结果安全区",
            ).value
            val hasSearchQuery = playlistSearchHasQuery(search.query)
            var searchReturnPosition by remember { mutableStateOf<Pair<Int, Int>?>(null) }
            LaunchedEffect(hasSearchQuery, search.expanded, wide, searchCoverScrollOffset) {
                if (wide) return@LaunchedEffect
                withFrameNanos { }
                if (hasSearchQuery) {
                    if (searchReturnPosition == null) {
                        searchReturnPosition = informationScroll.firstVisibleItemIndex to
                            informationScroll.firstVisibleItemScrollOffset
                    }
                    informationScroll.animateScrollToItem(0, searchCoverScrollOffset)
                } else {
                    searchReturnPosition?.let { (index, offset) ->
                        val lastIndex = informationScroll.layoutInfo.totalItemsCount - 1
                        if (lastIndex >= 0) informationScroll.animateScrollToItem(index.coerceAtMost(lastIndex), offset)
                    }
                    searchReturnPosition = null
                }
            }
            var closing by remember { mutableStateOf(false) }
            val closeAfterScroll = {
                if (menu?.expanded == true) {
                    menu.back()
                } else if (search.expanded) {
                    search.close()
                } else if (!closing) {
                    closing = true
                    scope.launch {
                        if (!wide && motion?.phase != MotionPhase.DRAGGING) {
                            informationScroll.quickScrollToTop()
                            repeat(2) { withFrameNanos { } }
                        }
                        onBack()
                    }
                }
            }
            val canLocate = state.currentTrack?.id?.let { currentId ->
                playlist.tracks.any { it.id == currentId }
            } == true
            val locateCurrentTrack: () -> Unit = locate@{
                val currentId = state.currentTrack?.id ?: return@locate
                val trackIndex = playlist.tracks.indexOfFirst { it.id == currentId }
                if (trackIndex < 0) return@locate
                search.close()
                scope.launch {
                    repeat(2) { withFrameNanos { } }
                    (if (wide) tracksScroll else informationScroll).locatePlaylistTrack(wide, trackIndex,
                        extraHeaderItems = if (tablet && !wide) 2 else 0)
                }
            }
            SideEffect {
                toolbarState.update(
                    playlistId = playlist.id,
                    showCollection = showCollection,
                    saved = saved,
                    updating = updatingCollection,
                    onBack = closeAfterScroll,
                    onToggleSaved = onToggleSaved,
                )
                floatingToolsState.update(
                    playlistId = playlist.id,
                    search = search,
                    canLocate = canLocate,
                    onLocate = locateCurrentTrack,
                )
            }
            DisposableEffect(playlist.id, toolbarState, floatingToolsState) {
                onDispose {
                    toolbarState.clear(playlist.id)
                    floatingToolsState.clear(playlist.id)
                }
            }
            BackHandler(enabled = LocalEntityActive.current && LocalPlayerMotion.current?.mounted != true) {
                if (search.expanded) search.close() else closeAfterScroll()
            }
            val introduction: @Composable () -> Unit = {
                Column {
                    PlaylistIntroduction(
                        playlist, sort, onPlay, onShuffle, onSort,
                        fixedLayout = wide,
                        showPlaybackActions = !tablet,
                        showDescription = !tablet,
                        horizontalPadding = if (tablet) 0.dp else 24.dp,
                        titleMaxLines = if (tablet) 2 else Int.MAX_VALUE,
                        hasCustomBackground = hasCustomBackground,
                        onChooseCustomBackground = onChooseCustomBackground,
                        onRestoreInheritedBackground = onRestoreInheritedBackground,
                    )
                    (collectionMessage ?: LocalMusicFavorites.current.state.message)?.takeUnless { tablet }?.let {
                        androidx.compose.material3.Text(
                            it,
                            Modifier.padding(20.dp).playlistDetailReveal(),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            val tabletDescription: @Composable () -> Unit = {
                Column {
                    PlaylistDescription(playlist.description,
                        showInDialog = true, album = playlist.isQqSearchAlbum, centered = true)
                    (collectionMessage ?: LocalMusicFavorites.current.state.message)?.let {
                        androidx.compose.material3.Text(it, Modifier.padding(20.dp),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (wide) {
                Box(pageMotionModifier) {
                    Row(Modifier.fillMaxSize()) {
                        if (tablet) TabletPlaylistPane(
                            scroll = informationScroll,
                            bottomInset = bottomInset,
                            contentBottomPadding = bottomInset,
                            usePageBackground = usePageBackground,
                            wide = true,
                            album = playlist.isQqSearchAlbum,
                            cover = { PlaylistCover(playlist, it.then(gestureModifier), usePageBackground) },
                            introduction = introduction,
                            actions = { PlaylistPlaybackActions(sort, playlist.tracks.isNotEmpty(), onPlay, onShuffle, onSort, playlist.source) },
                            description = tabletDescription,
                            modifier = Modifier.weight(.43f),
                        ) else Column(
                            modifier = Modifier.weight(.43f).fillMaxHeight().padding(bottom = bottomInset + 24.dp),
                        ) {
                            PlaylistCover(playlist, coverModifier.then(gestureModifier), usePageBackground)
                            introduction()
                        }
                        LazyColumn(
                            state = tracksScroll,
                            modifier = Modifier.weight(.57f).fillMaxHeight(),
                            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = contentBottomPadding),
                        ) {
                            if (contentMounted) {
                                playlistTrackItems(visiblePlaylist, state, favoriteIds, onTrackClick, onFavorite,
                                    loading = loading, dataEntrance = dataEntrance,
                                    loadMessage = if (search.query.isNotBlank() && visibleTracks.isEmpty()) "没有找到相关歌曲" else loadMessage,
                                    searchResults = searchResults)
                            }
                        }
                    }
                }
            } else if (tablet) {
                TabletPlaylistPane(
                    scroll = informationScroll,
                    bottomInset = bottomInset,
                    contentBottomPadding = contentBottomPadding,
                    usePageBackground = usePageBackground,
                    wide = false,
                    album = playlist.isQqSearchAlbum,
                    cover = { PlaylistCover(playlist, it.onSizeChanged { size -> search.coverHeightPx = size.height }, usePageBackground) },
                    introduction = introduction,
                    actions = { PlaylistPlaybackActions(sort, playlist.tracks.isNotEmpty(), onPlay, onShuffle, onSort, playlist.source) },
                    description = tabletDescription,
                    modifier = pageMotionModifier,
                ) {
                    if (contentMounted) playlistTrackItems(visiblePlaylist, state, favoriteIds, onTrackClick, onFavorite,
                        horizontalPadding = 20.dp, loading = loading, dataEntrance = dataEntrance,
                        loadMessage = if (search.query.isNotBlank() && visibleTracks.isEmpty()) "没有找到相关歌曲" else loadMessage,
                        searchResults = searchResults)
                }
            } else {
                Box(pageMotionModifier) {
                    LazyColumn(
                        state = informationScroll,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = contentBottomPadding),
                    ) {
                        item { PlaylistCover(playlist, coverModifier, usePageBackground) }
                        item { introduction() }
                        if (contentMounted) {
                            playlistTrackItems(visiblePlaylist, state, favoriteIds, onTrackClick, onFavorite,
                                horizontalPadding = 20.dp, loading = loading, dataEntrance = dataEntrance,
                                loadMessage = if (search.query.isNotBlank() && visibleTracks.isEmpty()) "没有找到相关歌曲" else loadMessage,
                                searchResults = searchResults)
                        }
                    }
                }
            }
        }
    }
}

internal fun playlistContentAlpha(progress: Float): Float = progress.coerceIn(.001f, 1f)

private suspend fun LazyListState.quickScrollToTop() {
    if (firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) return
    // 很长的歌单先跳到首屏附近，再完成短促可见的回顶动画。
    if (firstVisibleItemIndex > 1) scrollToItem(1)
    animateScrollToItem(0)
}
