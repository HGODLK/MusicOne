package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun mainSourceHomeColumns(width: Dp, height: Dp): Int = when {
    usesTabletLandscape(width, height) -> 3
    width >= 600.dp -> 3
    else -> 2
}

internal fun mainSourceHomeContentMaxWidth(width: Dp, height: Dp): Dp = when {
    usesTabletLandscape(width, height) -> 1_180.dp
    width >= 600.dp -> 900.dp
    else -> width
}

internal fun LazyStaggeredGridScope.mainSourceHomeItems(
    feed: MainSourceHomeContent,
    currentTrackId: String?,
    playing: Boolean,
    onTrackClick: (MusicTrack) -> Unit,
    actions: PlatformHomeActions,
    onRetry: () -> Unit,
    exitProgress: () -> Float,
    footerBottomInset: Dp = 0.dp,
    artworkSizes: MainSourceFeedArtworkSizes = MainSourceFeedArtworkSizes(),
    edgeInset: Dp = 0.dp,
) {
    val generation = feed.generation
    val refreshing = feed.refreshing
    if (feed.initialLoading) {
        item(key = "music-feed-initial-loading", span = StaggeredGridItemSpan.FullLine) {
            MainSourceHomeLoadingShelf()
        }
    }
    // 批量描述列表，避免每次追加都为全部历史卡片重新创建单项区间和闭包。
    items(
            items = feed.cards,
            key = { card -> "$generation:${card.key}" },
            contentType = { card -> when (card) {
                is MainSourceHomeCard.Playlist -> "playlist"
                is MainSourceHomeCard.Song -> "song"
                is MainSourceHomeCard.SongGroup -> "song-group"
                is MainSourceHomeCard.SongShelf -> "song-shelf"
            } },
            // 三歌曲组是音乐流中的一个普通瀑布流卡位，不能像上方三行推荐一样占满整行。
            span = { card -> if (card is MainSourceHomeCard.SongShelf) {
                StaggeredGridItemSpan.FullLine
            } else StaggeredGridItemSpan.SingleLane },
        ) { card ->
            androidx.compose.runtime.CompositionLocalProvider(LocalMainSourceFeedArtworkSizes provides artworkSizes) {
            // 扩宽放在淡入淡出图层外，刷新中间帧也能绘制到首页边缘。
            val cardModifier = if (card is MainSourceHomeCard.SongShelf) {
                Modifier.mainSourceRecommendationEdgeLayout(edgeInset)
            } else Modifier
            MainSourceHomeCardMotion(refreshing, exitProgress, cardModifier) {
                when (card) {
                    is MainSourceHomeCard.Playlist -> MainSourcePlaylistCard(
                        card.playlist, { actions.onOpenRecommendation(card.playlist) },
                        { actions.onPlayRecommendation(card.playlist) }, width = 1280.dp,
                        showSubtitle = true,
                        modifier = Modifier.fillMaxWidth(),
                        compactText = true,
                        artworkMaxSide = artworkSizes.card,
                    )
                    is MainSourceHomeCard.Song -> MainSourceHomeSongCard(card, card.track.id == currentTrackId,
                        playing && card.track.id == currentTrackId, { onTrackClick(card.track) })
                    is MainSourceHomeCard.SongGroup -> MainSourceHomeSongGroup(
                        card = card,
                        currentTrackId = currentTrackId,
                        playing = playing,
                        onTrackClick = onTrackClick,
                    )
                    is MainSourceHomeCard.SongShelf -> MainSourceHomeSongShelf(
                        shelf = card,
                        currentTrackId = currentTrackId,
                        playing = playing,
                        onTrackClick = onTrackClick,
                        showFavorites = card.section == MainSourceHomeSectionKind.SONG_RECOMMENDATION,
                        edgeInset = edgeInset,
                    )
                }
            }
            }
        }
    if (feed.message != null) {
        item(key = "music-feed-retry", span = StaggeredGridItemSpan.FullLine) {
            TextButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(feed.message)
            }
        }
        // 底部播放器悬浮在首页之上，错误/继续加载入口必须滚动到播放器上方后仍可见、可点击。
        item(key = "music-feed-footer-safe-space", span = StaggeredGridItemSpan.FullLine) {
            Spacer(Modifier.height(footerBottomInset + 48.dp))
        }
    }
}
