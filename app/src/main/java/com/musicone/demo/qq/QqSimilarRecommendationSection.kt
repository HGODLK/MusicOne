package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun QqSimilarRecommendationSection(
    state: QqSimilarRecommendationUiState,
    currentTrackId: String?,
    playing: Boolean,
    onTrackClick: (MusicTrack) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    edgeInset: Dp = 0.dp,
) {
    Crossfade(targetState = state.recommendations, animationSpec = musicMotion(420), label = "推荐歌曲整体刷新",
        modifier = modifier.qqRecommendationEdgeLayout(edgeInset)) { recommendations ->
        QqSimilarRecommendationContent(state.copy(recommendations = recommendations), currentTrackId, playing,
            onTrackClick, onRefresh, recommendations == state.recommendations, edgeInset)
    }
}

@Composable
private fun QqSimilarRecommendationContent(
    state: QqSimilarRecommendationUiState,
    currentTrackId: String?,
    playing: Boolean,
    onTrackClick: (MusicTrack) -> Unit,
    onRefresh: () -> Unit,
    interactive: Boolean,
    edgeInset: Dp,
) {
    val scope = rememberCoroutineScope()
    val baseTrack = state.baseTrack
    val recommendations = state.recommendations
    val favorites = LocalMusicFavorites.current
    val pagerState = rememberPagerState(pageCount = { recommendations.size.coerceAtLeast(1) })
    val pagerPosition by remember(pagerState) {
        derivedStateOf {
            qqSimilarRecommendationPagerPosition(
                pagerState.currentPage,
                pagerState.currentPageOffsetFraction,
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = qqSimilarRecommendationColumns((maxWidth - edgeInset * 2).value)
        val peek = if (maxWidth >= 600.dp) 56.dp else 42.dp
        val visibleSongCount = recommendations.maxOfOrNull { it.songs.size }
            ?: QQ_SIMILAR_RECOMMENDATION_SIZE
        val rows = qqSimilarRecommendationRows(visibleSongCount, columns)
        val pagerHeight = (rows * 76 + (rows - 1).coerceAtLeast(0) * 8).dp

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            QqSimilarRecommendationHeader(
                recommendations = recommendations,
                pagerPosition = pagerPosition,
                fallbackTitle = baseTrack?.let { "听「${it.title}」也会喜欢" } ?: "听最近播放的歌也会喜欢",
                loading = state.loading,
                refreshEnabled = interactive && recommendations.isNotEmpty() && !pagerState.isScrollInProgress,
                onRefresh = onRefresh,
                modifier = Modifier.padding(horizontal = edgeInset),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(pagerHeight).clipToBounds(),
                contentPadding = PaddingValues(start = edgeInset, end = edgeInset + peek),
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1,
                userScrollEnabled = interactive && recommendations.size > 1,
            ) { page ->
                recommendations.getOrNull(page)?.let { recommendation ->
                    QqSimilarSongGrid(
                        songs = recommendation.songs,
                        columns = columns,
                        favoriteIds = favorites.state.ids,
                        currentTrackId = currentTrackId,
                        playing = playing,
                        onTrackClick = { track ->
                            if (interactive) {
                                if (page == pagerState.settledPage && !pagerState.isScrollInProgress) onTrackClick(track)
                                else scope.launch { pagerState.animateScrollToPage(page) }
                            }
                        },
                        onFavorite = { if (interactive && page == pagerState.settledPage && !pagerState.isScrollInProgress) favorites.toggle(it) },
                        favoriteMotion = pageTitleMotion(page, pagerPosition),
                        favoritesEnabled = interactive && page == pagerState.settledPage &&
                            !pagerState.isScrollInProgress,
                    )
                } ?: QqSimilarLoadingGrid(columns)
            }
        }
    }
}
