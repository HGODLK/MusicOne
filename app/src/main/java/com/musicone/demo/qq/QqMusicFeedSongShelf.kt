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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 使用官方返回的三行分组，横向翻页只切换当前响应中的本地页。 */
@Composable
internal fun QqMusicFeedSongShelf(
    shelf: QqMusicFeedCard.SongShelf,
    currentTrackId: String?,
    playing: Boolean,
    onTrackClick: (MusicTrack) -> Unit,
    showFavorites: Boolean,
    modifier: Modifier = Modifier,
    edgeInset: Dp = 0.dp,
) {
    val pages = remember(shelf) { shelf.pages.filter { it.isNotEmpty() } }
    if (pages.isEmpty()) return

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = (maxWidth - edgeInset * 2).value
        val columns = qqSimilarRecommendationColumns(availableWidth)
        val rows = qqSimilarRecommendationRows(pages.maxOfOrNull { it.size } ?: 1, columns)
        val pagerHeight = (rows * 76 + (rows - 1).coerceAtLeast(0) * 8).dp
        val pagePeek = if (maxWidth >= 600.dp) 56.dp else 42.dp
        val pagerState = rememberPagerState(pageCount = { pages.size })
        val pagerPosition by remember(pagerState) {
            derivedStateOf {
                qqSimilarRecommendationPagerPosition(
                    pagerState.currentPage,
                    pagerState.currentPageOffsetFraction,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                shelf.title,
                Modifier.padding(horizontal = edgeInset),
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-.35).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(pagerHeight).clipToBounds(),
                contentPadding = PaddingValues(start = edgeInset, end = edgeInset + pagePeek),
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1,
            ) { page ->
                QqMusicFeedSongRows(
                    songs = pages[page],
                    columns = columns,
                    currentTrackId = currentTrackId,
                    playing = playing,
                    onTrackClick = onTrackClick,
                    showFavorites = showFavorites,
                    favoriteMotion = pageTitleMotion(page, pagerPosition),
                    favoritesEnabled = showFavorites && page == pagerState.settledPage &&
                        !pagerState.isScrollInProgress,
                )
            }
        }
    }
}

/** 三歌曲货架沿用同一套无互动杂项的三行歌曲行。 */
@Composable
internal fun QqMusicFeedSongGroup(
    card: QqMusicFeedCard.SongGroup,
    currentTrackId: String?,
    playing: Boolean,
    onTrackClick: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    QqMusicFeedSongGroupCard(
        card = card,
        currentTrackId = currentTrackId,
        playing = playing,
        onTrackClick = onTrackClick,
        modifier = modifier,
    )
}

@Composable
private fun QqMusicFeedSongRows(
    songs: List<QqMusicFeedCard.Song>,
    columns: Int,
    currentTrackId: String?,
    playing: Boolean,
    onTrackClick: (MusicTrack) -> Unit,
    showFavorites: Boolean,
    favoriteMotion: PageTitleMotion,
    favoritesEnabled: Boolean,
) {
    val favorites = LocalMusicFavorites.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        songs.chunked(columns.coerceAtLeast(1)).forEach { rowSongs ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowSongs.forEach { song ->
                    QqMusicFeedSongRow(
                        song = song,
                        current = song.track.id == currentTrackId,
                        playing = playing && song.track.id == currentTrackId,
                        onClick = { onTrackClick(song.track) },
                        favorite = showFavorites && song.track.isQqFavorite(favorites.state.ids),
                        onFavorite = { favorites.toggle(song.track) },
                        showFavorite = showFavorites,
                        favoriteMotion = favoriteMotion,
                        favoriteEnabled = favoritesEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowSongs.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QqMusicFeedSongRow(
    song: QqMusicFeedCard.Song,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    favorite: Boolean,
    onFavorite: () -> Unit,
    showFavorite: Boolean,
    favoriteMotion: PageTitleMotion,
    favoriteEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val track = song.track
    Row(
        modifier = modifier.height(76.dp)
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
                Modifier.fillMaxWidth(),
                25.sp,
                RoundedCornerShape(15.dp),
                maxSide = LocalQqFeedArtworkSizes.current.thumbnail,
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
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                track.title,
                color = if (current) trackPlaybackColor(track.source) else MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.recommendationTitle.isNotBlank()) {
                Text(
                    song.recommendationTitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                track.artists,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showFavorite) {
            AnimatedFavoriteButton(
                favorite = favorite,
                onClick = onFavorite,
                enabled = favoriteEnabled,
                modifier = Modifier.size(48.dp).qqRecommendationFavoriteMotion(favoriteMotion),
            )
        }
    }
}
