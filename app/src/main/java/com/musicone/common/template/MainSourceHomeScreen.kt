package com.musicone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.musicone.sourceintegration.SourcePackageController

@Composable
internal fun MainSourceHomeScreen(
    state: MusicOneUiState,
    catalog: MusicCatalogUiState,
    viewModel: MusicPlaybackRuntime,
    sessionRevision: Long,
    bottomInset: Dp,
    actions: PlatformHomeActions,
    source: MusicSource = catalog.source ?: MusicSource.LOCAL,
) {
    val sourceEntry = SourcePackageController.get(LocalContext.current).running(source.id)?.entry
    val similarViewModel: MainSourceSimilarRecommendationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val similar by similarViewModel.state.collectAsStateWithLifecycle()
    val recentPlayViewModel: MainSourceRecentPlayViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val recentPlay by recentPlayViewModel.state.collectAsStateWithLifecycle()
    ObserveMainSourceRecentPlayVisibility(recentPlayViewModel,
        state.page == MusicOnePage.HOME && LocalRecommendationVisible.current &&
            LocalPlayerMotion.current?.mounted != true)
    val feedViewModel: MainSourceHomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val feed by feedViewModel.content.collectAsStateWithLifecycle()
    val feedRefreshLoading by feedViewModel.refreshLoading.collectAsStateWithLifecycle()
    val homeRefresh = remember(similarViewModel, feedViewModel, actions.onRefreshRecommendedTracks) {
        MainSourceHomeRefresh(actions.onRefreshRecommendedTracks, similarViewModel::refresh, feedViewModel::refresh)
    }
    val feedExitProgress = rememberMainSourceFeedExitProgress(feed, feedViewModel::finishRefreshExit)
    val listState = rememberLazyStaggeredGridState()
    val personalizedVisible by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == "personalized" } }
    }
    ReportPrimaryHeaderScroll(MusicOnePage.HOME, listState)
    var today by remember { mutableStateOf(homeRefreshDay()) }
    val confirmedDay = catalog.recommendedTracksDate ?: today
    val dailyPlaylist = remember(confirmedDay, catalog.recommendedTracks) {
        val colors = mainSourceArtworkColors("每日推荐")
        MusicPlaylist(
            id = "${source.name.lowercase()}-daily-$confirmedDay",
            source = source,
            title = "每日推荐",
            subtitle = "每天更新 · ${catalog.recommendedTracks.size} 首",
            description = "根据你的${source.label}偏好生成的今日歌单",
            count = catalog.recommendedTracks.size,
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = "日",
            tracks = catalog.recommendedTracks,
            artworkUrl = catalog.recommendedTracks.firstOrNull()?.artworkUrl,
            providerData = DAILY_PLAYLIST_PROVIDER_DATA,
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        today = homeRefreshDay()
        homeRefresh.refresh(false)
        recentPlayViewModel.ensureLoaded()
    }
    LaunchedEffect(state.page) {
        if (state.page == MusicOnePage.HOME) {
            today = homeRefreshDay()
            homeRefresh.refresh(false)
        }
    }
    LaunchedEffect(source.id, sessionRevision) { recentPlayViewModel.configure(source); recentPlayViewModel.ensureLoaded() }
    LaunchedEffect(source.id, sessionRevision, recentPlay.snapshot.songs) {
        similarViewModel.configure(sessionRevision, recentPlay.snapshot.songs, source)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val artworkSizes = remember(maxWidth, maxHeight, density) { mainSourceFeedArtworkSizes(maxWidth, maxHeight, density) }
        val textPreparer = rememberMainSourceFeedTextPreparer(maxWidth, maxHeight, constraints.maxWidth)
        LaunchedEffect(maxWidth, maxHeight, bottomInset, artworkSizes, textPreparer) {
            feedViewModel.updateViewport(maxWidth, maxHeight - bottomInset, artworkSizes, textPreparer)
        }
        ObserveMainSourceHome(feedViewModel, listState, sessionRevision, state.page, source)
        val contentMax = mainSourceHomeContentMaxWidth(maxWidth, maxHeight)
        val gutter = if (maxWidth >= 600.dp) maxOf(32.dp, (maxWidth - contentMax) / 2) else 20.dp
        // 旧推荐区域的卡宽计算一并保留，当前由瀑布流列宽决定。
        // val cardWidth = if (maxWidth >= 600.dp) ((maxWidth - gutter * 2 - 48.dp) / 4).coerceIn(172.dp, 240.dp) else 152.dp
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(mainSourceHomeColumns(maxWidth, maxHeight)),
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 82.dp, bottom = bottomInset + 24.dp),
            verticalItemSpacing = 20.dp,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "personalized", span = StaggeredGridItemSpan.FullLine) {
                MainSourcePersonalizedCards(
                    dailyPlaylist = dailyPlaylist,
                    dailyDate = catalog.recommendedTracksDate ?: today,
                    dailyLoading = catalog.loadingRecommendedTracks,
                    dailyMessage = catalog.recommendedTracksMessage,
                    // 其他音源的电台在播放时，本音源的卡片不能显示为播放中。
                    radioActive = state.radioActive && state.currentTrack?.source == source,
                    radioPlaying = state.radioActive && state.currentTrack?.source == source && state.isPlaying,
                    radioLoading = state.radioLoading,
                    radioVisible = personalizedVisible && state.page == MusicOnePage.HOME &&
                        LocalRecommendationVisible.current && LocalPlayerMotion.current?.mounted != true,
                    onDailyClick = {
                        if (dailyPlaylist.tracks.isNotEmpty()) actions.onOpenLoadedPlaylist(dailyPlaylist)
                        else actions.onRefreshRecommendedTracks(true)
                    },
                    onRadioClick = { viewModel.startSourceRadio(source.id) },
                    modifier = Modifier,
                    radioEnabled = sourceEntry?.radio != null,
                    radioLabel = sourceEntry?.descriptor?.presentation?.radioLabel ?: "猜你喜欢",
                    radioColor = sourceEntry?.descriptor?.presentation?.radioColorArgb,
                    radioPaletteLight = sourceEntry?.descriptor?.presentation?.radioPaletteLight.orEmpty(),
                    radioPaletteDark = sourceEntry?.descriptor?.presentation?.radioPaletteDark.orEmpty(),
                )
            }
            if (sourceEntry?.discovery?.supportsSimilar == true) item(key = "similar-recent-play", span = StaggeredGridItemSpan.FullLine) {
                MainSourceSimilarRecommendationSection(
                    state = similar.copy(loading = similar.loading || feedRefreshLoading || catalog.loadingRecommendedTracks ||
                        (similar.baseTrack == null && recentPlay.loading)),
                    edgeInset = if (maxWidth < 600.dp) gutter else 0.dp,
                    currentTrackId = state.currentTrack?.id,
                    playing = state.isPlaying,
                    onTrackClick = viewModel::playRecommendationTrack,
                    onRefresh = {
                        today = homeRefreshDay()
                        homeRefresh.refresh(true)
                    },
                    modifier = Modifier,
                )
            }
            // 暂停旧推荐歌单区域，保留原实现供后续恢复。
            /*
            item(key = "recommendation-title") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("推荐歌单", fontSize = 23.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = (-.45).sp)
                        Text("适合现在听", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = actions.onRefreshRecommendations,
                        enabled = !catalog.loadingRecommendations,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = if (catalog.loadingRecommendations) {
                                "正在刷新推荐歌单"
                            } else {
                                "刷新推荐歌单"
                            }
                        },
                    ) {
                        if (catalog.loadingRecommendations) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                        }
                    }
                }
            }
            when {
                catalog.loadingRecommendations && catalog.recommendations.isEmpty() -> item(key = "loading") {
                    MainSourceInlineMessage("正在整理推荐歌单…", Modifier.padding(horizontal = 20.dp))
                }
                catalog.recommendations.isEmpty() -> item(key = "empty") {
                    MainSourceInlineMessage(
                        catalog.recommendationMessage ?: "暂时无法加载推荐歌单",
                        Modifier.padding(horizontal = 20.dp),
                    )
                }
                else -> item(key = "recommendations") {
                    if (maxWidth >= 700.dp && maxWidth > maxHeight) {
                        val columns = (maxWidth.value / 230f).toInt().coerceIn(3, 5)
                        Column(Modifier.fillMaxWidth().padding(horizontal = gutter),
                            verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            catalog.recommendations.chunked(columns).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    row.forEach { playlist -> MainSourcePlaylistCard(
                                        playlist, { actions.onOpenRecommendation(playlist) },
                                        { actions.onPlayRecommendation(playlist) }, width = 240.dp,
                                        modifier = Modifier.weight(1f))
                                    }
                                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                                }
                            }
                        }
                    } else LazyRow(
                        contentPadding = PaddingValues(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(catalog.recommendations, key = MusicPlaylist::id) { playlist ->
                            MainSourcePlaylistCard(
                                playlist = playlist,
                                onClick = { actions.onOpenRecommendation(playlist) },
                                onPlay = { actions.onPlayRecommendation(playlist) },
                                width = cardWidth,
                            )
                        }
                    }
                }
            }
            */
            mainSourceHomeItems(feed, state.currentTrack?.id, state.isPlaying, viewModel::playRecommendationTrack,
                actions, feedViewModel::retry, feedExitProgress,
                footerBottomInset = bottomInset, artworkSizes = artworkSizes,
                edgeInset = if (maxWidth < 600.dp) gutter else 0.dp)
            state.playbackMessage?.let { message ->
                item(key = "playback-message", span = StaggeredGridItemSpan.FullLine) {
                    MainSourceInlineMessage(message, Modifier.padding(horizontal = 20.dp))
                }
            }
        }

    }
}
