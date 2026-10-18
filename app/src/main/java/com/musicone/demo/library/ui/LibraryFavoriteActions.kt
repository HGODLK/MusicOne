package com.musicone.demo

import androidx.compose.runtime.*

/** QQ 收藏始终走 QQ 会话，本地和补充歌曲的收藏写入综合歌单。 */
@Composable
internal fun libraryFavoriteActions(remote: MusicFavoriteActions): MusicFavoriteActions {
    val ui = LocalMusicLibraryUi.current
    val tracks = ui.snapshot.unified.tracks
    val ids = remember(tracks) {
        tracks.filter { it.source.supportsLibraryEditing }.mapTo(hashSetOf()) { it.id }
    }
    val toggleLocal: (MusicTrack) -> Unit = remember(ui) { { track ->
        ui.work("正在更新综合歌单…") {
            // 点击时读取最新歌单，不能保留首次组合时的收藏状态。
            if (ui.snapshot.unified.tracks.any { it.id == track.id }) ui.store.removeTrack(UNIFIED_PLAYLIST_ID, track.id)
            else ui.store.addTracks(UNIFIED_PLAYLIST_ID, listOf(track))
        }
    } }
    return rememberLibraryFavoriteActions(remote, ids, toggleLocal)
}

/** 根界面重组时复用集合与动作，只把真实收藏变化交给下游。 */
@Composable
internal fun rememberLibraryFavoriteActions(remote: MusicFavoriteActions, localIds: Set<String>,
    toggleLocal: (MusicTrack) -> Unit): MusicFavoriteActions {
    val currentRemote = rememberUpdatedState(remote)
    val currentLocal = rememberUpdatedState(toggleLocal)
    val state = remember(remote.state, localIds) {
        if (localIds.isEmpty()) remote.state else remote.state.copy(ids = remote.state.ids + localIds)
    }
    val toggle: (MusicTrack) -> Unit = remember {
        { track -> if (track.source.supportsLibraryEditing) currentLocal.value(track) else currentRemote.value.toggle(track) }
    }
    val deferred: (MusicTrack) -> Unit = remember {
        { track -> if (track.source.supportsLibraryEditing) currentLocal.value(track) else currentRemote.value.toggleDeferredRemoval(track) }
    }
    val flush: (() -> Unit) -> Unit = remember {
        { complete -> currentRemote.value.flushDeferredRemovals(complete) }
    }
    val currentState: () -> MusicFavoriteUiState = remember {
        { currentRemote.value.currentState() }
    }
    return remember(state) { MusicFavoriteActions(state, toggle, deferred, flush, currentState) }
}
