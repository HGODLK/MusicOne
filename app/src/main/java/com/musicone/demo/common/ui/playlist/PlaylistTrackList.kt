package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

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
    searchResults: PlaylistSearchResults? = null,
) {
    val rows = searchResults?.rows ?: playlist.tracks.map(::PlaylistSearchRow)
    item("playlist-tracks-divider") {
        PlaylistSelectionToolbar()
        HorizontalDivider(Modifier.padding(horizontal = horizontalPadding).playlistDetailReveal(), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))
    }
    if (loading && rows.isEmpty()) {
        items(6, key = { "playlist-track-placeholder-$it" }) { PlaylistTrackPlaceholder(Modifier.padding(horizontal = horizontalPadding)) }
    } else if (rows.isEmpty()) {
        item("playlist-tracks-empty") { EmptyState(loadMessage ?: "暂时无法加载歌曲", Modifier.playlistDetailReveal()) }
    }
    items(rows, key = { it.track.id }, contentType = { "playlist-track" }) { row ->
        val track = row.track
        val content: @Composable () -> Unit = {
            PlaylistTrackRow(
                track = track,
                current = state.currentTrack?.id == track.id,
                playing = state.currentTrack?.id == track.id && state.isPlaying,
                favorite = track.id in favoriteIds,
                pendingRemoval = if (LocalPlaylistRemovals.current.playlistId == playlist.id)
                    track.id in LocalPlaylistRemovals.current.ids else track.id in LocalMusicFavorites.current.state.deferredRemovalIds,
                onClick = { if (searchResults == null || track.id in searchResults.targetIds) onTrackClick(track) },
                onFavorite = { if (searchResults == null || track.id in searchResults.targetIds) onFavorite(track) },
                modifier = Modifier
                    .padding(horizontal = horizontalPadding)
                    .graphicsLayer {
                        alpha = dataEntrance()
                        translationY = 24.dp.toPx() * (1f - alpha)
                    },
            )
        }
        if (searchResults == null) content() else PlaylistSearchRowVisibility(row, searchResults, content)
    }
    item("playlist-tracks-pagination") { PlaylistPaginationFooter() }
    if (playlist.tracks.isNotEmpty()) item("playlist-tracks-summary") {
        val more = LocalPlaylistPagination.current?.entry?.page?.next != null
        Text(
            if (more && playlist.count > playlist.tracks.size) "已加载 ${playlist.tracks.size} / ${playlist.count} 首歌曲"
            else if (more) "已加载 ${playlist.tracks.size} 首歌曲"
            else "${playlist.tracks.size} 首歌曲 · 约 ${playlist.tracks.sumOf { it.durationMs } / 60_000} 分钟",
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = 20.dp).playlistDetailReveal(),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        )
    }
}

@Composable
internal fun PlaylistTrackPlaceholder(modifier: Modifier) {
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
internal fun PlaylistTrackRow(
    track: MusicTrack,
    current: Boolean,
    playing: Boolean,
    favorite: Boolean,
    pendingRemoval: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier,
    onMore: ((Rect) -> Unit)? = null,
    enabled: Boolean = true,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val qqMenu = LocalQqPlaylistSongMenu.current
    val displayed = displayedPlaybackTrack(track)
    val removalActions = LocalPlaylistRemovals.current
    val libraryUi = LocalMusicLibraryUi.current
    val selection = LocalPlaylistMultiSelection.current.takeIf { qqMenu != null }
    val haptic = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    var menuAnchor by remember { mutableStateOf(Rect.Zero) }
    Column(modifier.playlistDetailReveal()) {
        Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled,
            onClick = { if (selection?.active == true) selection.toggle(track.id) else if (displayed.playable) onClick() },
            onLongClickLabel = "选择歌曲",
            hapticFeedbackEnabled = false,
            onLongClick = selection?.let { { haptic.performHapticFeedback(HapticFeedbackType.LongPress); it.begin(track.id) } },
        ).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
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
                TrackTitle(displayed, fontSize = 16.sp,
                    color = if (!displayed.playable) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
                        else if (current) trackPlaybackColor(track.source) else MaterialTheme.colorScheme.onSurface)
                Text(track.artists, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                supportingContent?.invoke()
            }
            if (selection?.active == true) {
                Box(Modifier.size(48.dp).toggleable(track.id in selection.ids, enabled = enabled, role = Role.Checkbox,
                    onValueChange = { selection.toggle(track.id) }), contentAlignment = Alignment.Center) {
                    Icon(if (track.id in selection.ids) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = "选择${track.title}", tint = MaterialTheme.colorScheme.onSurface)
                }
            } else Box {
                FavoriteRemovalAction(
                    pendingRemoval = pendingRemoval,
                    undoLabel = if (removalActions.playlistId.isNotBlank()) "撤销移出歌单" else "撤销取消收藏",
                    onMore = {
                    if (onMore != null) onMore(menuAnchor)
                    else if (qqMenu != null) qqMenu.open(track, menuAnchor) else menuExpanded = true
                    },
                    onUndo = { if (removalActions.playlistId.isNotBlank()) removalActions.set(track, false) else onFavorite() },
                    modifier = Modifier.onGloballyPositioned { menuAnchor = it.boundsInRoot() },
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(if (current && playing) "暂停" else "播放") },
                        enabled = displayed.playable,
                        leadingIcon = { Icon(if (current && playing) Icons.Default.Pause else Icons.Default.PlayArrow, null) },
                        onClick = { menuExpanded = false; onClick() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (favorite) "取消收藏" else "收藏歌曲") },
                        leadingIcon = { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null) },
                        onClick = { menuExpanded = false; onFavorite() },
                    )
                    if (track.source in setOf(MusicSource.NETEASE, MusicSource.KUGOU)) DropdownMenuItem(
                        text = { Text("加入综合") },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                        enabled = !libraryUi.busy,
                        onClick = { menuExpanded = false; libraryUi.addTo(UNIFIED_PLAYLIST_ID, track) },
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 64.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))
    }
}
