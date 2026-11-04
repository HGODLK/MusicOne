package com.musicone.source.kugou

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun KugouCollectionPage(controller: KugouPageController, player: MusicOneUiState, favorites: KugouFavoriteUiState) {
    val state by controller.page.collectAsState()
    val playlist = state.playlist ?: return
    var selected by remember(playlist.id) { mutableStateOf(emptySet<String>()) }
    var selecting by remember(playlist.id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 88.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(playlist.title, style = MaterialTheme.typography.headlineMedium); Text(playlist.description) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ controller.play(playlist) }, enabled = playlist.tracks.isNotEmpty()) { Text("播放全部") }
            OutlinedButton({ selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "取消多选" else "多选") }
            OutlinedButton({ controller.open(playlist) }, enabled = !state.playlistLoading) { Text("刷新") }
        } }
        if (selecting) item { Row {
            TextButton({ selected = playlist.tracks.mapTo(linkedSetOf()) { it.id } }) { Text("全选") }
            TextButton({ selected = emptySet() }) { Text("清空") }
            TextButton({ controller.showSelection(playlist.tracks.filter { it.id in selected }) }, enabled = selected.isNotEmpty()) { Text("操作 ${selected.size} 首") }
        } }
        if (state.playlistLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        state.playlistError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error); TextButton({ controller.open(playlist) }) { Text("重试") } } }
        items(playlist.tracks, key = MusicTrack::id) { track -> Row {
            if (selecting) Checkbox(track.id in selected, { checked -> selected = if (checked) selected + track.id else selected - track.id })
            MusicTrackRow(track, player.currentTrack?.id == track.id, player.isPlaying && player.currentTrack?.id == track.id,
                track.id in favorites.ids, { if (selecting) selected = if (track.id in selected) selected - track.id else selected + track.id else controller.play(track) }, { controller.favorite(track) }, false)
        } }
    }
}
