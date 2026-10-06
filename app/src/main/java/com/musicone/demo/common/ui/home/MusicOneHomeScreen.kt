package com.musicone.demo

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable
internal fun PlatformHomeLayout(
    state: MusicOneUiState,
    catalog: MusicCatalogUiState,
    viewModel: MusicOneViewModel,
    bottomInset: Dp,
    onOpenRecommendation: (MusicPlaylist) -> Unit,
    onPlayRecommendation: (MusicPlaylist) -> Unit,
    onRefreshRecommendedTracks: (Boolean) -> Unit,
) {
    val favorites = LocalMusicFavorites.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefreshRecommendedTracks(false) }
    LaunchedEffect(state.page) {
        if (state.page == MusicOnePage.HOME) onRefreshRecommendedTracks(false)
    }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    ReportPrimaryHeaderScroll(MusicOnePage.HOME, gridState)
    val density = LocalDensity.current
    val motion = LocalPlaylistMotion.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPlaylistCardViewport provides PlaylistCardViewport(
            (with(density) { gridBounds.height.toDp() } - bottomInset - 18.dp).coerceAtLeast(0.dp),
        ) { cardBounds ->
            gridState.stopScroll()
            val visible = gridBounds.copy(bottom = gridBounds.bottom - with(density) { (bottomInset + 18.dp).toPx() })
            val distance = playlistScrollDistance(cardBounds(), visible)
            if (distance != 0f) gridState.animateScrollBy(distance, animationSpec = musicMotion(360))
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().onGloballyPositioned { gridBounds = it.boundsInRoot() },
            userScrollEnabled = LocalPlayerMotion.current?.mounted != true && motion?.mounted != true &&
                LocalPlaylistCardTransition.current?.busy != true,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 82.dp, bottom = bottomInset + 18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SectionHeading("今日推荐", "为你整理的音乐氛围")
                        when {
                            catalog.loadingRecommendations -> Text("正在加载推荐…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            catalog.recommendations.isEmpty() -> EmptyState(
                                catalog.recommendationMessage ?: "暂时无法加载推荐歌单")
                            else -> PlaylistRecommendations(catalog.recommendations,
                                onOpenRecommendation, onPlayRecommendation)
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeading(
                        "推荐歌曲",
                        if (catalog.loadingRecommendedTracks) "正在更新" else "今天可以听这些",
                        actionLabel = if (catalog.loadingRecommendedTracks) "刷新中" else "刷新",
                        actionEnabled = !catalog.loadingRecommendedTracks,
                        onAction = { onRefreshRecommendedTracks(true) },
                    )
                }
                when {
                    catalog.loadingRecommendedTracks && catalog.recommendedTracks.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("正在加载推荐歌曲…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    catalog.recommendedTracks.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(catalog.recommendedTracksMessage ?: "暂时无法加载推荐歌曲")
                    }
                    else -> items(catalog.recommendedTracks, key = { it.id }) { track ->
                        MusicTrackRow(
                            track = track,
                            current = state.currentTrack?.id == track.id,
                            playing = state.isPlaying && state.currentTrack?.id == track.id,
                            favorite = track.id in favorites.state.ids,
                            onClick = { viewModel.playTrack(track) },
                            onFavorite = { favorites.toggle(track) },
                        )
                    }
                }
                catalog.actionMessage?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(message) }
                }
                state.playbackMessage?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(message) }
                }
                favorites.state.message?.let { message ->
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(message) }
                }
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    note: String,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-.5).sp))
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        if (actionLabel != null && onAction != null) {
            FilledTonalButton(
                onClick = onAction,
                enabled = actionEnabled,
                modifier = Modifier.heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(actionLabel, fontSize = 13.sp)
            }
        }
    }
}
