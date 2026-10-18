package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Stable
internal class PlaylistMultiSelection {
    var active by mutableStateOf(false)
    var ids by mutableStateOf(emptySet<String>())
    var tracks by mutableStateOf(emptyList<MusicTrack>())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var revision = 0
    fun update(tracks: List<MusicTrack>) {
        if (this.tracks === tracks) return
        this.tracks = tracks
        if (ids.isNotEmpty()) ids = ids.intersect(tracks.mapTo(hashSetOf()) { it.id })
    }
    fun toggle(id: String) { ids = if (id in ids) ids - id else ids + id }
    fun begin(id: String) { active = true; ids = ids + id }
    suspend fun selectAll(load: suspend () -> List<MusicTrack>) {
        if (loading) return
        val request = ++revision
        loading = true
        error = null
        try {
            val complete = load()
            if (request != revision) return
            tracks = complete
            ids = complete.mapTo(hashSetOf()) { it.id }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (request == revision) error = failure.asUserMessage()
        } finally {
            if (request == revision) loading = false
        }
    }
    fun close() { revision++; active = false; ids = emptySet(); loading = false; error = null }
}

internal val LocalPlaylistMultiSelection = staticCompositionLocalOf<PlaylistMultiSelection?> { null }
internal val LocalPlaylistRemovalLabel = staticCompositionLocalOf<String?> { null }

@Composable
internal fun PlaylistSelectionToolbar(loadAll: (suspend () -> List<MusicTrack>)? = null) {
    val selection = LocalPlaylistMultiSelection.current ?: return
    val menu = LocalQqPlaylistSongMenu.current ?: return
    val pagination = LocalPlaylistPagination.current
    val scope = rememberCoroutineScope()
    val available = !menu.busy && !selection.loading
    var anchor by remember { mutableStateOf(Rect.Zero) }
    AnimatedVisibility(selection.active,
        enter = expandVertically(musicMotion(220)) + fadeIn(musicMotion(180)),
        exit = shrinkVertically(musicMotion(180)) + fadeOut(musicMotion(140))) {
        FlowRow(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp),
            itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("已选 ${selection.ids.size}", Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurface)
            SelectionAction(if (selection.loading) "正在全选…" else "全选", available) {
                scope.launch { selection.selectAll { loadAll?.invoke() ?: pagination?.selectionTracks() ?: selection.tracks } }
            }
            selection.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Box(Modifier.onGloballyPositioned { anchor = it.boundsInRoot() }) {
                SelectionAction("添加到", selection.ids.isNotEmpty() && available) {
                    menu.openBatch(selection.tracks.filter { it.id in selection.ids }, anchor)
                }
            }
            LocalPlaylistRemovalLabel.current?.let { label ->
                SelectionAction(label, selection.ids.isNotEmpty() && available) {
                    menu.openBatch(selection.tracks.filter { it.id in selection.ids }, anchor, chooseTargets = false)
                }
            }
            SelectionAction("取消", !menu.busy, selection::close)
        }
    }
}

@Composable
private fun SelectionAction(label: String, enabled: Boolean = true, action: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, onClick = action).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
    }
}
