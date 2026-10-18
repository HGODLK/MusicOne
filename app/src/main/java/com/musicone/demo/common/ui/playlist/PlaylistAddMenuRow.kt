package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PlaylistAddMenuRow(
    label: String, target: MusicPlaylist, menu: QqPlaylistSongMenuState, onChanged: (MusicPlaylist) -> Unit = {},
) {
    val ui = LocalMusicLibraryUi.current
    val liveTarget = if (target.isManagedLibrary) ui.snapshot.playlists.firstOrNull { it.id == target.id } ?: target else target
    val selected = menu.additions.selected
    val removals = LocalPlaylistRemovals.current
    val members = menu.additions.members(liveTarget) - if (removals.playlistId == target.id) removals.ids else emptySet()
    val candidates = remember(target.id, selected) { playlistAdditionCandidates(target, selected) }
    val added = candidates.isNotEmpty() && candidates.all { it.id in members }
    val checking = target.id in menu.additions.checking
    val tint = trackPlaybackColor(menu.track?.source ?: target.source)
    LaunchedEffect(menu.additions.generation, target.id) { menu.additions.check(target) }
    SongMenuRow(label, !menu.busy && !ui.busy && !checking && candidates.isNotEmpty(), trailing = {
        Box(Modifier.size(24.dp)) {
            if (menu.writingTarget == target.id || checking) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
            else if (added) Icon(Icons.Default.Check, "已添加", tint = tint, modifier = Modifier.fillMaxSize())
        }
    }) {
        menu.toggleTarget(liveTarget, onChanged)
    }
}
