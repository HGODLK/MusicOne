package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MusicTrackRow(
    track: MusicTrack,
    current: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    favoriteContent: (@Composable () -> Unit)? = null,
    showSource: Boolean = true,
) {
    val menu = LocalQqPlaylistSongMenu.current
    var anchor by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier = Modifier.clickable(onClickLabel = "播放${track.title}", onClick = onClick)
            .semantics { contentDescription = "播放歌曲：${track.title}" }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp)) {
            RemoteArtwork(
                track.artworkUrl, track.artworkStart, track.artworkEnd, track.artworkMark,
                Modifier.fillMaxSize(), 28.sp, RoundedCornerShape(16.dp),
            )
            TrackPlaybackArtworkIndicator(
                current = current,
                playing = playing,
                shape = RoundedCornerShape(16.dp),
                iconSize = 25.dp,
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TrackTitle(
                track,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = if (current) trackPlaybackColor(track.source) else MaterialTheme.colorScheme.onSurface,
            )
            Text("${track.artists} · ${track.album}", color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showSource) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(track.source.accent)))
                Text("  ${track.source.label}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
        }
        if (current) Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
        if (favoriteContent != null) favoriteContent() else IconButton(onClick = onFavorite) {
            Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "收藏", tint = if (favorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        }
        if (menu != null) IconButton(onClick = { menu.open(track, anchor) },
            modifier = Modifier.size(48.dp).onGloballyPositioned { anchor = it.boundsInRoot() }) {
            Icon(Icons.Default.MoreHoriz, contentDescription = "更多",
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
