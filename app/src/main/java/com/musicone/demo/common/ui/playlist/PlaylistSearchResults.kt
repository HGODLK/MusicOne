package com.musicone.demo

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.*

internal data class PlaylistSearchRow(val track: MusicTrack, val initiallyVisible: Boolean = true)

/** 退出行保留实际布局空间；重新命中时反向续接，不交给 LazyColumn 绘制脱离布局的旧图层。 */
internal class PlaylistSearchResults(initial: List<MusicTrack>) {
    var rows by mutableStateOf(initial.map(::PlaylistSearchRow)); private set
    var targetIds by mutableStateOf<Set<String>>(initial.mapTo(mutableSetOf()) { it.id }); private set
    var animated by mutableStateOf(false); private set
    private val mountedIds = mutableSetOf<String>()

    fun update(tracks: List<MusicTrack>, animate: Boolean) {
        animated = animate
        targetIds = tracks.mapTo(mutableSetOf()) { it.id }
        rows = mergePlaylistSearchRows(rows, tracks, if (animate) mountedIds else emptySet(), animate)
    }

    fun mount(id: String) { mountedIds.add(id) }
    fun unmount(id: String) {
        mountedIds.remove(id)
        finishExit(id)
    }

    fun finishExit(id: String) {
        // 迟到的退出回调不能删除已经被新查询重新命中的歌曲。
        if (id !in targetIds) rows = rows.filterNot { it.track.id == id }
    }

    fun finishEntrance(id: String) {
        rows = rows.map { if (it.track.id == id && !it.initiallyVisible) it.copy(initiallyVisible = true) else it }
    }
}

internal fun mergePlaylistSearchRows(previous: List<PlaylistSearchRow>, tracks: List<MusicTrack>,
    mountedIds: Set<String>, animated: Boolean): List<PlaylistSearchRow> {
    val targets = tracks.distinctBy { it.id }
    val targetIds = targets.mapTo(mutableSetOf()) { it.id }
    val oldById = previous.associateBy { it.track.id }
    val before = mutableMapOf<String?, MutableList<PlaylistSearchRow>>()
    var nextId: String? = null
    previous.asReversed().forEach { row ->
        if (row.track.id in targetIds) nextId = row.track.id
        else if (row.track.id in mountedIds) before.getOrPut(nextId) { mutableListOf() }.add(row)
    }
    return buildList {
        targets.forEach { track ->
            before[track.id]?.asReversed()?.let(::addAll)
            add(PlaylistSearchRow(track, oldById[track.id]?.initiallyVisible ?: !animated))
        }
        before[null]?.asReversed()?.let(::addAll)
    }
}

@Composable
internal fun rememberPlaylistSearchResults(tracks: List<MusicTrack>, animated: Boolean): PlaylistSearchResults {
    val results = remember { PlaylistSearchResults(tracks) }
    SideEffect { results.update(tracks, animated) }
    return results
}

@Composable
internal fun PlaylistSearchRowVisibility(row: PlaylistSearchRow, results: PlaylistSearchResults,
    content: @Composable () -> Unit) {
    val id = row.track.id
    val transition = remember { MutableTransitionState(row.initiallyVisible) }
    transition.targetState = id in results.targetIds
    DisposableEffect(results, id) {
        results.mount(id)
        onDispose { results.unmount(id) }
    }
    LaunchedEffect(transition.currentState, transition.targetState, transition.isIdle) {
        if (transition.isIdle && !transition.currentState && !transition.targetState) results.finishExit(id)
        else if (transition.isIdle && transition.currentState) results.finishEntrance(id)
    }
    SearchResultRowVisibility(transition, results.animated, reserveEntrySpace = true, content = content)
}
