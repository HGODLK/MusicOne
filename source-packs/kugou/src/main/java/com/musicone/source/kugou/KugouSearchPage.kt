package com.musicone.source.kugou

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourceSearchKind

@Composable internal fun KugouSearchPage(controller: KugouPageController, player: MusicOneUiState, favorites: KugouFavoriteUiState) {
    val state by controller.page.collectAsState()
    var query by remember { mutableStateOf(state.query) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var selecting by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row {
            TextButton({ controller.back() }) { Text("返回") }
            OutlinedTextField(query, { query = it }, label = { Text("搜索歌曲、歌单、专辑") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton({ controller.search(query) }) { Text("搜索") }
        }
        Row { listOf(SourceSearchKind.TRACK to "歌曲", SourceSearchKind.COLLECTION to "歌单", SourceSearchKind.ALBUM to "专辑").forEach { (kind, label) ->
            FilterChip(state.searchKind == kind, { controller.search(query, kind) }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
        } }
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.searchError?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ controller.search(query) }) { Text("重试") } }
        if (query.isBlank()) {
            Row { Text("搜索历史", Modifier.weight(1f)); TextButton({ controller.clearSearchHistory(); controller.page.value = state.copy(message = "已清空搜索历史") }) { Text("清空") } }
            controller.searchHistory().forEach { old -> TextButton({ query = old; controller.search(old) }) { Text(old) } }
        }
        if (state.searchKind == SourceSearchKind.TRACK && state.searchTracks.isNotEmpty()) Row {
            TextButton({ selecting = !selecting; selected = emptySet() }) { Text(if (selecting) "取消多选" else "多选") }
            if (selecting) { TextButton({ selected = state.searchTracks.mapTo(linkedSetOf()) { it.id } }) { Text("全选") }
                TextButton({ selected = emptySet() }) { Text("清空") }
                TextButton({ controller.showSelection(state.searchTracks.filter { it.id in selected }) }, enabled = selected.isNotEmpty()) { Text("操作 ${selected.size} 首") } }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.searchKind == SourceSearchKind.TRACK) items(state.searchTracks, key = MusicTrack::id) { track -> Row {
                if (selecting) Checkbox(track.id in selected, { checked -> selected = if (checked) selected + track.id else selected - track.id })
                MusicTrackRow(track, player.currentTrack?.id == track.id, player.isPlaying && player.currentTrack?.id == track.id,
                    track.id in favorites.ids, { if (selecting) selected = if (track.id in selected) selected - track.id else selected + track.id else controller.play(track) }, { controller.favorite(track) }, false)
            } } else items(state.searchCollections, key = MusicPlaylist::id) { list ->
                ListItem(headlineContent = { Text(list.title) }, supportingContent = { Text(list.subtitle) }, trailingContent = { TextButton({ controller.open(list) }) { Text("打开") } })
            }
        }
    }
}
