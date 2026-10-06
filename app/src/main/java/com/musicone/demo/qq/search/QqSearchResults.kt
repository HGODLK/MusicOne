package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 结果保留各分类滚动状态，详情在根层覆盖其上。 */
@Composable
internal fun QqSearchResults(
    state: QqSearchState, model: QqSearchViewModel, motion: QqSearchMotion,
    player: MusicOneUiState, bottomInset: Dp, onTrack: (MusicTrack) -> Unit,
    onCollection: (MusicPlaylist) -> Unit, blocked: Boolean,
) {
    val pager = rememberPagerState { QqSearchTab.entries.size }
    val scrolls = QqSearchTab.entries.associateWith { rememberSaveable(state.revision, saver = LazyListState.Saver) { LazyListState() } }
    var scrollRevision by rememberSaveable { mutableIntStateOf(state.revision) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.revision) {
        if (scrollRevision != state.revision) { pager.scrollToPage(0); scrollRevision = state.revision }
    }
    LaunchedEffect(pager.currentPage, state.submitted, state.revision, state.full, motion.resultsReady, motion.fullMoving) {
        if (state.full && motion.resultsReady && !motion.fullMoving) model.load(QqSearchTab.entries[pager.currentPage])
    }
    if (!state.full && !motion.resultsReady && !motion.fullMoving) return
    val entities = LocalEntityNavigation.current
    val menuContext = remember { MusicPlaylist("qq-search-context", MusicSource.QQ, "搜索", "", "", 0,
        0L, 0L, "", emptyList()) }
    QqPlaylistSongMenuHost(menuContext, emptyList(), bottomInset, {}, {}, LocalMusicFavorites.current.toggle) {
    QqSearchTheme {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val geometry = searchGeometry(maxWidth, maxHeight, state, motion)
    Box(Modifier.fillMaxSize().graphicsLayer {
        val frame = geometry()
        shape = QqSearchRevealShape { frame }
        clip = true
        alpha = playerSurfaceReveal(motion.full.value)
    }.background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().padding(top = 56.dp).graphicsLayer {
            translationY = 32.dp.toPx() * (1f - motion.full.value)
        }) {
            if (motion.resultsReady) HorizontalPager(pager, Modifier.fillMaxSize().qqSearchTopFade(), userScrollEnabled = !blocked && !motion.fullMoving, key = { it }) { index ->
                val tab = QqSearchTab.entries[index]
                val incoming = state.pages[tab] ?: QqSearchPage(loading = true)
                var displayed by remember { mutableStateOf(incoming) }
                if (!motion.fullMoving) SideEffect { displayed = incoming }
                key(state.revision) { SearchResultList(displayed, tab, scrolls.getValue(tab),
                    player, bottomInset, onTrack, onCollection, { entities?.open(EntityTarget.Artist(it), sourceKey = "search:${it.id}") },
                    onMore = { model.load(tab, more = true) }, onRetry = {
                        val page = state.pages[tab]
                        model.load(tab, more = page?.page != null && page.page > 0, retry = true)
                    }) }
            }
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                QqSearchTab.entries.forEachIndexed { index, tab ->
                    val selected = pager.currentPage == index
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(index, animationSpec = musicMotion(360)) } },
                        modifier = Modifier.weight(1f).semantics { this.selected = selected }, contentPadding = PaddingValues(0.dp)) {
                        Text(tab.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) QqMusicThemeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.graphicsLayer {
                                val distance = abs(pager.currentPage - index + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                                alpha = 1f - distance * .3f
                                translationY = distance * 3.dp.toPx()
                            })
                    }
                }
            }
        }
    }
    }
    }
    }
}

@Composable
private fun SearchResultList(
    page: QqSearchPage, tab: QqSearchTab, scroll: LazyListState, player: MusicOneUiState, bottomInset: Dp,
    onTrack: (MusicTrack) -> Unit, onCollection: (MusicPlaylist) -> Unit, onSinger: (QqSearchSinger) -> Unit,
    onMore: () -> Unit, onRetry: () -> Unit,
) {
    val favorites = LocalMusicFavorites.current
    val folds = rememberSearchResultFolds()
    val all = tab == QqSearchTab.ALL
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val padding = maxOf(20.dp, (maxWidth - 1040.dp) / 2)
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = padding, end = padding, top = 84.dp, bottom = bottomInset + 24.dp)) {
            if (tab == QqSearchTab.ALL && page.songs.isNotEmpty()) item("heading-songs") { Box(Modifier.animateItem(placementSpec = musicMotion(320))) { SearchGroupTitle("歌曲") } }
            searchResultItems("songs", page.songs, 12, all, folds, key = { "song-${it.id}" }) { track ->
                Box(Modifier.animateItem(fadeInSpec = musicMotion(260), placementSpec = musicMotion(320), fadeOutSpec = musicMotion(220))) { SearchResultEntrance {
                MusicTrackRow(track, player.currentTrack?.id == track.id,
                    player.currentTrack?.id == track.id && player.isPlaying, track.id in favorites.state.ids,
                    onClick = { onTrack(track) }, onFavorite = { favorites.toggle(track) },
                    favoriteContent = {
                        AnimatedFavoriteButton(track.id in favorites.state.ids, { favorites.toggle(track) }, Modifier.size(48.dp))
                    }, showSource = false)
                } }
            }
            searchFoldButton("songs", page.songs.size, 12, all, folds)
            if (tab == QqSearchTab.ALL && page.singers.isNotEmpty()) item("heading-singers") { Box(Modifier.animateItem(placementSpec = musicMotion(320))) { SearchGroupTitle("歌手") } }
            searchResultItems("singers", page.singers, 5, all, folds, key = { "singer-${it.id}" }) { singer ->
                Box(Modifier.animateItem(fadeInSpec = musicMotion(260), placementSpec = musicMotion(320), fadeOutSpec = musicMotion(220))) { SearchResultEntrance { SearchEntityRow(singer.name, "歌手", singer.artwork, singer.id, true) { onSinger(singer) } } }
            }
            searchFoldButton("singers", page.singers.size, 5, all, folds)
            if (tab == QqSearchTab.ALL && page.albums.isNotEmpty()) item("heading-albums") { Box(Modifier.animateItem(placementSpec = musicMotion(320))) { SearchGroupTitle("专辑") } }
            searchResultItems("albums", page.albums, 5, all, folds, key = { it.id }) { album -> Box(Modifier.animateItem(fadeInSpec = musicMotion(260), placementSpec = musicMotion(320), fadeOutSpec = musicMotion(220))) { SearchResultEntrance { SearchCollectionRow(album, onCollection) } } }
            searchFoldButton("albums", page.albums.size, 5, all, folds)
            if (tab == QqSearchTab.ALL && page.playlists.isNotEmpty()) item("heading-playlists") { Box(Modifier.animateItem(placementSpec = musicMotion(320))) { SearchGroupTitle("歌单") } }
            searchResultItems("playlists", page.playlists, 5, all, folds, key = { it.id }) { playlist -> Box(Modifier.animateItem(fadeInSpec = musicMotion(260), placementSpec = musicMotion(320), fadeOutSpec = musicMotion(220))) { SearchResultEntrance { SearchCollectionRow(playlist, onCollection) } } }
            searchFoldButton("playlists", page.playlists.size, 5, all, folds)
            item {
                AnimatedContent(when { page.loading -> "loading"; page.error != null -> "error"; page.empty -> "empty"; else -> "ready" },
                    transitionSpec = { (fadeIn(musicMotion(240)) + slideInVertically { it / 5 }) togetherWith
                        (fadeOut(musicMotion(200)) + slideOutVertically { -it / 5 }) }, label = "搜索结果反馈") { status ->
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (status) {
                            "loading" -> Text("正在搜索…")
                            "error" -> { Text(page.error.orEmpty()); TextButton(onRetry) { Text("重试") } }
                            "empty" -> Text("没有找到相关内容")
                            else -> if (page.hasMore) TextButton(onMore) { Text("加载更多") }
                        }
                    }
                }
                if (!all && page.hasMore && !page.loading && page.error == null) LaunchedEffect(page.page) { onMore() }
            }
        }
    }
}

@Composable
private fun SearchGroupTitle(title: String) {
    Text(title, Modifier.padding(top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun SearchCollectionRow(playlist: MusicPlaylist, onClick: (MusicPlaylist) -> Unit) {
    SearchEntityRow(playlist.title, playlist.subtitle, playlist.artworkUrl, playlist.id, false,
        playlist = playlist) { onClick(playlist) }
}

@Composable
private fun SearchEntityRow(
    title: String, subtitle: String, artworkUrl: String?, id: String, round: Boolean,
    playlist: MusicPlaylist? = null, onClick: () -> Unit,
) {
    val artwork by rememberArtworkBitmap(artworkUrl)
    val transition = LocalPlaylistCardTransition.current
    val motion = LocalPlaylistMotion.current
    val shape = if (round) CircleShape else RoundedCornerShape(12.dp)
    val type = when {
        round -> "歌手"
        playlist?.isQqSearchAlbum == true -> "专辑"
        else -> "歌单"
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = motion?.mounted != true && transition?.busy != true) {
        if (playlist == null || transition == null) onClick()
        else transition.open(id, artwork, {}, onClick)
    }.semantics { contentDescription = "打开$type：$title" }
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkBitmapOrPlaceholder(artwork, playlist?.artworkStart ?: 0xFF7BAA9B, playlist?.artworkEnd ?: 0xFF374A43,
            title.take(1), Modifier.size(64.dp).then(if (playlist != null)
                Modifier.motionAnchor(motion, id, false, corner = 12f, markSize = 24f, markX = 0f, markY = 0f)
                else Modifier.entityArtworkAnchor("search:$id", 32f)),
            24.sp, shape, Alignment.Center)
        Column(Modifier.weight(1f).padding(start = 16.dp).graphicsLayer {
            alpha = if (playlist != null) transition?.alphaFor(id) ?: 1f else 1f
        }) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
