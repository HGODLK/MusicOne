package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun LazyListScope.playlistTrackItems(
    playlist: MusicPlaylist,
    state: MusicOneUiState,
    favoriteIds: Set<String>,
    onTrackClick: (MusicTrack) -> Unit,
    onFavorite: (MusicTrack) -> Unit,
    horizontalPadding: Dp = 0.dp,
    loading: Boolean = false,
    loadMessage: String? = null,
    dataEntrance: () -> Float = { 1f },
    searchMotionActive: Boolean = false,
) {
    item {
        HorizontalDivider(Modifier.padding(horizontal = horizontalPadding).playlistDetailReveal(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))
    }
    if (loading) {
        items(6) { PlaylistTrackPlaceholder(Modifier.padding(horizontal = horizontalPadding)) }
    } else if (playlist.tracks.isEmpty()) {
        item { EmptyState(loadMessage ?: "暂时无法加载歌曲", Modifier.playlistDetailReveal()) }
    }
    items(playlist.tracks, key = { it.id }) { track ->
        PlaylistTrackRow(
            track = track,
            current = state.currentTrack?.id == track.id,
            playing = state.currentTrack?.id == track.id && state.isPlaying,
            favorite = track.id in favoriteIds,
            pendingRemoval = track.id in LocalMusicFavorites.current.state.deferredRemovalIds,
            onClick = { onTrackClick(track) },
            onFavorite = { onFavorite(track) },
            modifier = Modifier
                .padding(horizontal = horizontalPadding)
                .then(if (searchMotionActive) Modifier.animateItem(
                    fadeInSpec = musicMotion(240),
                    placementSpec = musicMotion(320),
                    fadeOutSpec = musicMotion(180),
                ) else Modifier)
                .graphicsLayer {
                    alpha = dataEntrance()
                    translationY = 24.dp.toPx() * (1f - alpha)
                },
        )
    }
    if (playlist.tracks.isNotEmpty()) item {
        Text(
            "${playlist.tracks.size} 首歌曲 · 约 ${playlist.tracks.sumOf { it.durationMs } / 60_000} 分钟",
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 20.dp).playlistDetailReveal(),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        )
    }
}

@Composable
private fun PlaylistTrackPlaceholder(modifier: Modifier) {
    Column(modifier.playlistDetailReveal()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth(.46f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)))
                Box(Modifier.fillMaxWidth(.28f).height(11.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .05f)))
            }
            Box(Modifier.size(48.dp))
        }
        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))
    }
}

@Composable
private fun PlaylistTrackRow(
    track: MusicTrack,
    current: Boolean,
    playing: Boolean,
    favorite: Boolean,
    pendingRemoval: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val qqMenu = LocalQqPlaylistSongMenu.current
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    Column(modifier.playlistDetailReveal()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp)) {
                RemoteArtwork(track.artworkUrl, track.artworkStart, track.artworkEnd, track.artworkMark,
                    Modifier.fillMaxSize(), 23.sp, RoundedCornerShape(16.dp))
                TrackPlaybackArtworkIndicator(
                    current = current,
                    playing = playing,
                    shape = RoundedCornerShape(16.dp),
                    iconSize = 24.dp,
                    modifier = Modifier.matchParentSize(),
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                TrackTitle(track, fontSize = 16.sp,
                    color = if (current) trackPlaybackColor(track.source) else MaterialTheme.colorScheme.onSurface)
                Text(track.artists, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                FavoriteRemovalAction(
                    pendingRemoval = pendingRemoval,
                    onMore = {
                    if (qqMenu != null) qqMenu.open(track, menuAnchor) else menuExpanded = true
                    },
                    onUndo = onFavorite,
                    modifier = Modifier.onGloballyPositioned { menuAnchor = it.boundsInRoot() },
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (current && playing) "暂停" else "播放") },
                        leadingIcon = { Icon(if (current && playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) },
                        onClick = { menuExpanded = false; onClick() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (favorite) "取消收藏" else "收藏歌曲") },
                        leadingIcon = { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                        onClick = { menuExpanded = false; onFavorite() },
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))
    }
}
