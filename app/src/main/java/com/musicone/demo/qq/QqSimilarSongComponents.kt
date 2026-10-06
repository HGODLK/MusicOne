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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.layout
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
internal fun QqSimilarRecommendationHeader(
    recommendations: List<QqSimilarRecommendation>,
    pagerPosition: Float,
    fallbackTitle: String,
    loading: Boolean,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleIndex = pagerPosition.roundToInt().coerceIn(0, recommendations.lastIndex.coerceAtLeast(0))
    val spokenTitle = recommendations.getOrNull(titleIndex)?.title ?: fallbackTitle
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).heightIn(min = 56.dp).clipToBounds().semantics {
                contentDescription = spokenTitle
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (recommendations.isEmpty()) {
                RecommendationTitle(fallbackTitle, Modifier.clearAndSetSemantics {})
            } else {
                val travel = with(LocalDensity.current) { 32.dp.toPx() }
                recommendations.forEachIndexed { index, recommendation ->
                    val motion = pageTitleMotion(index, pagerPosition)
                    if (motion.alpha > 0f) {
                        RecommendationTitle(
                            recommendation.title,
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    translationX = motion.offsetFraction * travel
                                    alpha = motion.alpha
                                }
                                .clearAndSetSemantics {},
                        )
                    }
                }
            }
        }
        QqRefreshButton(
            loading = loading,
            enabled = refreshEnabled,
            contentDescription = "刷新歌曲推荐",
            onClick = onRefresh,
        )
    }
}

@Composable
private fun RecommendationTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-.4).sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun QqSimilarSongGrid(
    songs: List<QqSimilarSong>,
    columns: Int,
    favoriteIds: Set<String>,
    currentTrackId: String?,
    playing: Boolean,
    onTrackClick: (MusicTrack) -> Unit,
    onFavorite: (MusicTrack) -> Unit,
    favoriteMotion: PageTitleMotion,
    favoritesEnabled: Boolean,
) {
    Column(Modifier.fillMaxWidth().clipToBounds(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        songs.chunked(columns).forEach { rowSongs ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowSongs.forEach { song ->
                    QqSimilarSongRow(
                        song = song,
                        favorite = song.track.isQqFavorite(favoriteIds),
                        favoriteMotion = favoriteMotion,
                        favoriteEnabled = favoritesEnabled,
                        current = song.track.id == currentTrackId,
                        playing = playing && song.track.id == currentTrackId,
                        onClick = { onTrackClick(song.track) },
                        onFavorite = { onFavorite(song.track) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowSongs.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QqSimilarSongRow(
    song: QqSimilarSong,
    favorite: Boolean,
    favoriteMotion: PageTitleMotion,
    favoriteEnabled: Boolean,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = song.track
    Row(
        modifier = modifier
            .height(76.dp)
            .clickable(onClickLabel = "播放${track.title}", onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(62.dp)) {
            RemoteArtwork(
                track.artworkUrl,
                track.artworkStart,
                track.artworkEnd,
                track.artworkMark,
                Modifier.fillMaxSize(),
                25.sp,
                RoundedCornerShape(15.dp),
            )
            TrackPlaybackArtworkIndicator(
                current = current,
                playing = playing,
                shape = RoundedCornerShape(15.dp),
                iconSize = 24.dp,
            )
        }
        Column(
            Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TrackTitle(
                track,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (current) trackPlaybackColor(track.source) else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                track.artists,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedFavoriteButton(
            favorite = favorite,
            onClick = onFavorite,
            enabled = favoriteEnabled,
            modifier = Modifier.size(48.dp).qqRecommendationFavoriteMotion(favoriteMotion),
        )
    }
}

internal fun Modifier.qqRecommendationFavoriteMotion(motion: PageTitleMotion): Modifier = graphicsLayer {
    alpha = motion.alpha
    translationX = motion.offsetFraction * 24.dp.toPx()
}

@Composable
internal fun QqSimilarLoadingGrid(columns: Int) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until QQ_SIMILAR_RECOMMENDATION_SIZE).chunked(columns).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { _ -> QqSimilarLoadingRow(Modifier.weight(1f)) }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QqSimilarLoadingRow(modifier: Modifier = Modifier) {
    Row(
        modifier.height(76.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(62.dp), RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surfaceVariant) {}
        Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(width = 132.dp, height = 12.dp), RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant) {}
            Surface(Modifier.size(width = 88.dp, height = 9.dp), RoundedCornerShape(5.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)) {}
        }
    }
}

internal fun qqSimilarRecommendationPagerPosition(currentPage: Int, offsetFraction: Float): Float =
    currentPage + offsetFraction

internal fun qqSimilarRecommendationRows(songCount: Int, columns: Int): Int =
    (songCount.coerceAtLeast(1) + columns.coerceAtLeast(1) - 1) / columns.coerceAtLeast(1)

internal fun qqSimilarRecommendationColumns(availableWidthDp: Float): Int = when {
    availableWidthDp >= 960f -> 3
    availableWidthDp >= 620f -> 2
    else -> 1
}
