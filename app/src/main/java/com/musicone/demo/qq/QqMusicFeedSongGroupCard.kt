package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 音乐流的三歌曲组是一个独立瀑布流卡位，保留官方返回的组标题和三首歌曲。 */
@Composable
internal fun QqMusicFeedSongGroupCard(
    card: QqMusicFeedCard.SongGroup,
    currentTrackId: String?,
    playing: Boolean,
    onTrackClick: (MusicTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tracks = card.tracks.take(3)
    if (tracks.isEmpty()) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                card.recommendationTitle.ifBlank { "为你推荐的歌曲" },
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            tracks.forEach { track ->
                QqMusicFeedSongGroupRow(
                    track = track,
                    current = track.id == currentTrackId,
                    playing = playing && track.id == currentTrackId,
                    onClick = { onTrackClick(track) },
                )
            }
        }
    }
}

@Composable
private fun QqMusicFeedSongGroupRow(
    track: MusicTrack,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 62.dp)
            .clickable(onClickLabel = "播放${track.title}", onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(50.dp)) {
            RemoteArtwork(
                track.artworkUrl,
                track.artworkStart,
                track.artworkEnd,
                track.artworkMark,
                Modifier.fillMaxWidth(),
                22.sp,
                RoundedCornerShape(13.dp),
                maxSide = LocalQqFeedArtworkSizes.current.thumbnail,
            )
            TrackPlaybackArtworkIndicator(
                current = current,
                playing = playing,
                shape = RoundedCornerShape(13.dp),
                iconSize = 22.dp,
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(start = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TrackTitle(
                track = track,
                fontSize = 14.sp,
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
    }
}
