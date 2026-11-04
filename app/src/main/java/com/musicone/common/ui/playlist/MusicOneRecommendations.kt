package com.musicone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class RecommendationCardLayout(
    val widthDp: Float,
    val heightDp: Float,
    val compact: Boolean,
)

internal fun recommendationCardLayout(availableWidthDp: Float): RecommendationCardLayout {
    val available = availableWidthDp.coerceAtLeast(1f)
    val compact = available < 600f
    val width = if (compact) {
        (available * .74f).coerceIn(226f, 264f).coerceAtMost(available)
    } else {
        (available * .31f).coerceIn(260f, 300f).coerceAtMost(available)
    }
    val aspectRatio = if (compact) .82f else .84f
    return RecommendationCardLayout(width, width / aspectRatio, compact)
}

/** 横滑视口扩宽后，卡宽仍按页面原有内容区计算，不能改变手机与平板断点。 */
internal fun recommendationShelfCardLayout(viewportWidthDp: Float, edgeInsetDp: Float): RecommendationCardLayout =
    recommendationCardLayout(viewportWidthDp - edgeInsetDp * 2)

@Composable
internal fun PlaylistRecommendations(playlists: List<MusicPlaylist>, onOpen: (MusicPlaylist) -> Unit, onPlay: (MusicPlaylist) -> Unit) {
    PlaylistRecommendationsContent(playlists, 0.dp, PaddingValues(end = 20.dp), onOpen, onPlay)
}

@Composable
internal fun PlaylistRecommendations(playlists: List<MusicPlaylist>, edgeInset: Dp,
    onOpen: (MusicPlaylist) -> Unit, onPlay: (MusicPlaylist) -> Unit) {
    PlaylistRecommendationsContent(playlists, edgeInset, PaddingValues(horizontal = edgeInset), onOpen, onPlay)
}

@Composable
private fun PlaylistRecommendationsContent(playlists: List<MusicPlaylist>, edgeInset: Dp,
    contentPadding: PaddingValues, onOpen: (MusicPlaylist) -> Unit, onPlay: (MusicPlaylist) -> Unit) {
    BoxWithConstraints(Modifier.mainSourceRecommendationEdgeLayout(edgeInset).fillMaxWidth()) {
        val gap = 14.dp
        val layout = recommendationShelfCardLayout(maxWidth.value, edgeInset.value)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(gap),
            contentPadding = contentPadding,
            userScrollEnabled = LocalPlaylistMotion.current?.mounted != true && LocalPlaylistCardTransition.current?.busy != true,
        ) {
            items(playlists, key = { it.id }) { playlist ->
                FeaturePlaylistCard(
                    playlist = playlist,
                    modifier = Modifier.width(layout.widthDp.dp),
                    desiredHeight = layout.heightDp.dp,
                    compact = layout.compact,
                    onPlay = { onPlay(playlist) },
                ) { onOpen(playlist) }
            }
        }
    }
}
