package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 按设置与音源能力路由红心动作：
 * 未声明我喜欢能力的音源（含本地和可编辑资料的补充源）始终写入综合歌单；
 * 已声明能力的音源按“我喜欢按钮功能”决定只写音源、只写综合歌单或双写。
 */
@Composable
internal fun libraryFavoriteActions(remote: MusicFavoriteActions): MusicFavoriteActions {
    val ui = LocalMusicLibraryUi.current
    val context = LocalContext.current
    val installed by remember(context) {
        com.musicone.sourceintegration.SourcePackageController.get(context).installed
    }.collectAsStateWithLifecycle()
    val behavior = ExperiencePreferences.options.favoriteButtonBehavior
    val tracks = ui.snapshot.unified.tracks
    val setUnified: (MusicTrack, Boolean) -> Unit = remember(ui) { { track, liked ->
        ui.work("正在更新综合歌单…") {
            // 点击时读取最新综合歌单，避免沿用旧快照导致重复写入或漏删。
            val identities = playlistTrackIdentities(track)
            val existing = ui.snapshot.unified.tracks.firstOrNull { candidate ->
                playlistTrackIdentities(candidate).any(identities::contains)
            }
            if (liked) {
                if (existing == null) ui.store.addTracks(UNIFIED_PLAYLIST_ID, listOf(track))
            } else if (existing != null) {
                ui.store.removeTrack(UNIFIED_PLAYLIST_ID, existing.id)
            }
        }
    } }
    return rememberLibraryFavoriteActions(
        remote = remote,
        unifiedTracks = tracks,
        behavior = behavior,
        sourceRevision = installed,
        supportsRemote = MusicSource::supportsRemoteFavorites,
        currentUnifiedTracks = { ui.snapshot.unified.tracks },
        setUnified = setUnified,
    )
}

/** 缓存收藏集合和回调引用，避免根页面重组时把所有子列表一起带动刷新。 */
@Composable
internal fun rememberLibraryFavoriteActions(
    remote: MusicFavoriteActions,
    unifiedTracks: List<MusicTrack>,
    behavior: FavoriteButtonBehavior,
    sourceRevision: Any,
    supportsRemote: (MusicSource) -> Boolean,
    currentUnifiedTracks: () -> List<MusicTrack>,
    setUnified: (MusicTrack, Boolean) -> Unit,
): MusicFavoriteActions {
    val currentRemote = rememberUpdatedState(remote)
    val currentBehavior = rememberUpdatedState(behavior)
    val currentSupportsRemote = rememberUpdatedState(supportsRemote)
    val currentUnified = rememberUpdatedState(currentUnifiedTracks)
    val currentSetUnified = rememberUpdatedState(setUnified)
    val ids = remember(remote.state.ids, unifiedTracks, behavior, sourceRevision) {
        displayedFavoriteIds(remote.state.ids, unifiedTracks, behavior, supportsRemote)
    }
    val state = remember(remote.state, ids) {
        if (ids === remote.state.ids) remote.state else remote.state.copy(ids = ids)
    }
    val toggle: (MusicTrack) -> Unit = remember {
        { track ->
            val unifiedIds = buildSet { currentUnified.value().forEach { addAll(playlistTrackIdentities(it)) } }
            val plan = planFavoriteToggle(
                track = track,
                behavior = currentBehavior.value,
                supportsRemote = currentSupportsRemote.value(track.source),
                remoteIds = currentRemote.value.currentState().ids,
                unifiedIds = unifiedIds,
            )
            plan.unifiedTarget?.let { currentSetUnified.value(track, it) }
            plan.remoteTarget?.let { currentRemote.value.setFavorite(track, it) }
        }
    }
    val setFavorite: (MusicTrack, Boolean) -> Unit = remember {
        { track, liked ->
            val remoteSupported = currentSupportsRemote.value(track.source)
            when {
                !remoteSupported || currentBehavior.value == FavoriteButtonBehavior.UNIFIED_ONLY ->
                    currentSetUnified.value(track, liked)
                currentBehavior.value == FavoriteButtonBehavior.SOURCE_ONLY ->
                    currentRemote.value.setFavorite(track, liked)
                else -> {
                    currentSetUnified.value(track, liked)
                    currentRemote.value.setFavorite(track, liked)
                }
            }
        }
    }
    val deferred: (MusicTrack) -> Unit = remember {
        { track ->
            if (!currentSupportsRemote.value(track.source)) {
                val unifiedIds = buildSet { currentUnified.value().forEach { addAll(playlistTrackIdentities(it)) } }
                currentSetUnified.value(track, !track.isMainSourceFavorite(unifiedIds))
            } else {
                // “我喜欢”歌单详情里的延迟移除只移出对应音源的“我喜欢”，不误删综合歌单。
                currentRemote.value.toggleDeferredRemoval(track)
            }
        }
    }
    val flush: (() -> Unit) -> Unit = remember {
        { complete -> currentRemote.value.flushDeferredRemovals(complete) }
    }
    val currentState: () -> MusicFavoriteUiState = remember {
        {
            val remoteState = currentRemote.value.currentState()
            val latestIds = displayedFavoriteIds(
                remoteState.ids,
                currentUnified.value(),
                currentBehavior.value,
                currentSupportsRemote.value,
            )
            if (latestIds === remoteState.ids) remoteState else remoteState.copy(ids = latestIds)
        }
    }
    return remember(state) { MusicFavoriteActions(state, toggle, deferred, flush, currentState, setFavorite) }
}
