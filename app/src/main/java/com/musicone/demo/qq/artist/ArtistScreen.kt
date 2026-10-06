package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

@Composable
internal fun ArtistScreen(singer: QqSearchSinger, player: MusicOneUiState, bottomInset: Dp,
    created: List<MusicPlaylist>, onBack: () -> Unit,
    onPlay: (List<MusicTrack>, MusicTrack?) -> Unit, onChanged: (MusicPlaylist) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val artworkAliases = remember { QqArtworkAliasStore(context) }
    val data = remember(singer.id) { ArtistPageState(singer,
        QqArtistRepository(PlatformPreferences(context).readSession(MusicSource.QQ), artworkAliases),
        artworkAliases, scope) }
    LaunchedEffect(data) { data.start() }
    val search = rememberPlaylistSearchState("artist:${singer.id}")
    val frame = LocalEntityFrame.current
    val pager = rememberPagerState { 2 }
    val songsScroll = rememberLazyListState()
    val albumsScroll = rememberLazyListState()
    val favorites = LocalMusicFavorites.current
    val motion = LocalPlaylistMotion.current
    val activation = motion?.let { rememberPlaylistContentActivation(singer.id, it) }
    val songsEntrance = rememberEntityDataEntrance(data.songs.isNotEmpty())
    val tracks = remember(data.songs, data.searchSongs, search.query) {
        if (search.query.isBlank()) data.songs else
            (data.songs.matchingPlaylistQuery(search.query) + data.searchSongs).distinctBy { it.id }
    }
    LaunchedEffect(search.query, data.songSort, data.profile.singerId) {
        data.updateSearch(search.query)
    }
    val playlist = MusicPlaylist("qq-artist-${singer.id}", MusicSource.QQ, data.profile.name, "", "",
        data.songTotal, 0xFFCEDCD7, 0xFF779187, singer.name.take(1), tracks, data.profile.artwork)
    val backdrop = rememberGraphicsLayer()
    val coverPager = rememberPagerState { data.profile.backgrounds.size.coerceAtLeast(1) }
    val appearance = rememberArtistAppearance(data.profile.backgrounds, coverPager)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    MaterialTheme(colorScheme = appearance.colors) {
    CompositionLocalProvider(LocalContentColor provides appearance.colors.onSurface,
        LocalPlaylistContentEntrance provides { activation?.entrance?.value ?: 1f }) {
    QqPlaylistSongMenuHost(playlist, created, bottomInset, {}, onChanged, favorites.toggle) {
        val menu = LocalQqPlaylistSongMenu.current
        BoxWithConstraints(Modifier.fillMaxSize().background(appearance.background).onGloballyPositioned { bounds = it.boundsInRoot() }) {
            val wide = usesTabletLandscape(maxWidth, maxHeight)
            val density = LocalDensity.current
            val heroHeight = (maxHeight * .68f).coerceIn(360.dp, 640.dp)
            val heroPx = with(density) { heroHeight.toPx() }
            val collapseLimit = heroPx - with(density) { 64.dp.toPx() }
            var collapsed by rememberSaveable(singer.id) { mutableFloatStateOf(0f) }
            val searchMotionActive = rememberPlaylistSearchMotionActive(search.expanded)
            val imeBottomInset = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
            val contentBottomPadding = animateDpAsState(
                playlistSearchResultsBottomPadding(bottomInset, imeBottomInset, search.expanded),
                musicMotion(240),
                label = "歌手歌曲搜索安全区",
            ).value
            val hasSearchQuery = playlistSearchHasQuery(search.query)
            var searchReturnCollapsed by remember(singer.id) { mutableStateOf<Float?>(null) }
            var searchReturnPosition by remember(singer.id) { mutableStateOf<Pair<Int, Int>?>(null) }
            LaunchedEffect(hasSearchQuery, search.expanded, wide, collapseLimit) {
                if (wide) return@LaunchedEffect
                if (hasSearchQuery) {
                    if (searchReturnCollapsed == null) searchReturnCollapsed = collapsed
                    if (searchReturnPosition == null) {
                        searchReturnPosition = songsScroll.firstVisibleItemIndex to songsScroll.firstVisibleItemScrollOffset
                    }
                    coroutineScope {
                        launch { animate(collapsed, collapseLimit, animationSpec = musicMotion(300)) { value, _ -> collapsed = value } }
                        launch { songsScroll.animateScrollToItem(0) }
                    }
                } else {
                    val returnCollapsed = searchReturnCollapsed
                    val returnPosition = searchReturnPosition
                    coroutineScope {
                        if (returnCollapsed != null) launch {
                            animate(collapsed, returnCollapsed, animationSpec = musicMotion(260)) { value, _ -> collapsed = value }
                        }
                        if (returnPosition != null) launch {
                            val lastIndex = songsScroll.layoutInfo.totalItemsCount - 1
                            if (lastIndex >= 0) songsScroll.animateScrollToItem(
                                returnPosition.first.coerceAtMost(lastIndex),
                                returnPosition.second,
                            )
                        }
                    }
                    searchReturnCollapsed = null
                    searchReturnPosition = null
                }
            }
            val returnAction = rememberArtistReturnAction(menu, search, motion, wide, { collapsed }, { collapsed = it }, onBack)
            SideEffect {
                frame?.backAction = returnAction
                frame?.statusBarColor = appearance.background
                frame?.floatingTools?.apply {
                    visible = pager.targetPage == 0
                    update("artist:${singer.id}", search,
                        data.songs.any { it.id == player.currentTrack?.id }) {
                        val index = data.songs.indexOfFirst { it.id == player.currentTrack?.id }
                        if (index >= 0) {
                            search.close(); collapsed = collapseLimit
                            scope.launch { withFrameNanos { }; songsScroll.animateScrollToItem(index + 2) }
                        }
                    }
                }
            }
            LaunchedEffect(pager.targetPage) { if (pager.targetPage != 0) search.close() }
            ArtistPagination(data, songsScroll, albumsScroll, pager.currentPage,
                LocalEntityActive.current && activation?.mounted != false, hasSearchQuery)
            val gesture = Modifier.playlistPullGesture(
                if (pager.currentPage == 0) songsScroll else albumsScroll,
                motion.takeIf { LocalEntityActive.current && menu?.expanded != true && !search.expanded },
                LocalPlaylistPull.current,
                onBack,
                canStart = { wide || collapsed <= 0f },
            )
            val contentMounted = activation?.mounted != false
            val body: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    if (data.playAllLoading || data.playAllError != null) Text(
                        data.playAllError ?: "正在准备全部歌曲…", Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    TabRow(pager.currentPage, containerColor = appearance.background) {
                        listOf("歌曲 ${data.songTotal}", "专辑 ${data.albumTotal}").forEachIndexed { index, title ->
                            Tab(pager.currentPage == index, onClick = { scope.launch {
                                pager.animateScrollToPage(index, animationSpec = musicMotion(280))
                            } }, text = { Text(title) })
                        }
                    }
                    HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { tab ->
                        if (tab == 0) LazyColumn(state = songsScroll, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = contentBottomPadding)) {
                            if (contentMounted) item("song-sort") { ArtistSongSortButton(data.songSort) {
                                data.toggleSongSort()
                                scope.launch { songsScroll.scrollToItem(0) }
                            } }
                            if (contentMounted) playlistTrackItems(playlist, player, favorites.state.ids,
                                { onPlay(tracks, it) }, favorites.toggle,
                                loading = if (hasSearchQuery) data.searchLoading && tracks.isEmpty()
                                    else data.songLoading && data.songs.isEmpty(),
                                loadMessage = if (hasSearchQuery) {
                                    data.searchError?.takeIf { tracks.isEmpty() }
                                        ?: if (!data.searchLoading && tracks.isEmpty()) "没有找到相关歌曲" else null
                                } else data.songError ?: "暂无歌曲",
                                dataEntrance = { songsEntrance.value }, searchMotionActive = searchMotionActive)
                            if (contentMounted) item {
                                if (hasSearchQuery) ArtistLoadMore(data.searchLoading, data.searchError,
                                    data.searchNext != null, data::loadSearchSongs)
                                else ArtistLoadMore(data.songLoading, data.songError, data.songNext != null, data::loadSongs)
                            }
                        } else ArtistAlbumList(data, albumsScroll, bottomInset, contentMounted)
                    }
                }
            }
            Box(Modifier.fillMaxSize().then(gesture).playerQualityBackdropSnapshot(backdrop, menu?.expanded == true)) {
                if (wide) Row(Modifier.fillMaxSize()) {
                    ArtistIntroduction(data.profile, Modifier.weight(.43f).fillMaxHeight().padding(bottom = bottomInset),
                        !data.playAllLoading, { data.playAll(onPlay) }, coverPager, appearance)
                    Box(Modifier.weight(.57f).padding(top = 72.dp)) { body() }
                } else ArtistCollapsingLayout(
                    heroHeight = heroHeight,
                    collapseLimit = collapseLimit,
                    collapsed = { collapsed },
                    onCollapsed = { collapsed = it },
                    list = if (pager.currentPage == 0) songsScroll else albumsScroll,
                    header = {
                        ArtistIntroduction(
                            data.profile,
                            Modifier.fillMaxSize(),
                            !data.playAllLoading,
                            { data.playAll(onPlay) },
                            coverPager,
                            appearance,
                        )
                    },
                    content = body,
                )
            }
            PlaylistToolbar(backdrop, bounds, returnAction)
            if (!wide) {
                val toolbarFadeDistance = with(density) { 64.dp.toPx() }
                Box(Modifier.fillMaxWidth().height(64.dp).drawBehind {
                    val alpha = ((collapsed - collapseLimit + toolbarFadeDistance) / toolbarFadeDistance).coerceIn(0f, 1f)
                    drawRect(appearance.background.copy(alpha = alpha))
                })
                Text(
                    data.profile.name,
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                        .graphicsLayer { alpha = if (collapsed >= collapseLimit - 1f) 1f else 0f }
                        .clearAndSetSemantics { },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (LocalEntityActive.current && LocalPlayerMotion.current?.mounted != true) BackHandler { returnAction() }
        }
    }
    }
    }
}
