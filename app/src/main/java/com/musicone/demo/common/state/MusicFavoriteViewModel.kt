package com.musicone.demo

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MusicFavoriteUiState(
    val ids: Set<String> = emptySet(),
    val updating: Set<String> = emptySet(),
    val message: String? = null,
    val changes: Map<String, Pair<MusicTrack, Boolean>> = emptyMap(),
    val deferredRemovalIds: Set<String> = emptySet(),
)

internal data class MusicFavoriteActions(
    val state: MusicFavoriteUiState = MusicFavoriteUiState(),
    val toggle: (MusicTrack) -> Unit = {},
    val toggleDeferredRemoval: (MusicTrack) -> Unit = {},
    val flushDeferredRemovals: (() -> Unit) -> Unit = { it() },
)

internal val LocalMusicFavorites = staticCompositionLocalOf { MusicFavoriteActions() }

internal class MusicFavoriteViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicFavoriteRepository(application)
    private val _state = MutableStateFlow(MusicFavoriteUiState())
    val state: StateFlow<MusicFavoriteUiState> = _state.asStateFlow()
    private var configurationKey = ""
    private var loadJob: Job? = null
    private val deferredRemovals = linkedMapOf<String, MusicTrack>()
    private val deferredPreviousChanges = mutableMapOf<String, Pair<MusicTrack, Boolean>?>()
    private val deferredFlushMutex = Mutex()
    private val qqReconcileTracks = linkedMapOf<String, MusicTrack>()
    private var qqReconcileJob: Job? = null

    fun configure(source: MusicSource, sessionRevision: Long) {
        val key = "$source\n$sessionRevision"
        if (configurationKey == key) return
        flushDeferredRemovals()
        configurationKey = key
        loadJob?.cancel()
        qqReconcileJob?.cancel()
        qqReconcileJob = null
        qqReconcileTracks.clear()
        _state.value = MusicFavoriteUiState()
        loadJob = viewModelScope.launch {
            val ids = runCatching { repository.likedTrackIds(source) }.getOrDefault(emptySet())
            if (configurationKey == key) _state.update { state ->
                val updated = ids.toMutableSet()
                state.changes.forEach { (id, change) -> if (change.second) updated.add(id) else updated.remove(id) }
                state.copy(ids = updated)
            }
        }
    }

    fun toggleDeferredRemoval(track: MusicTrack) {
        cancelQqReconcile()
        if (track.id in _state.value.updating) return
        if (deferredRemovals.remove(track.id) != null) {
            val previous = deferredPreviousChanges.remove(track.id)
            _state.update { state -> state.restoreFavoriteRemoval(track, previous) }
            return
        }
        deferredPreviousChanges[track.id] = _state.value.changes[track.id]
        deferredRemovals[track.id] = track
        _state.update { state -> state.stageFavoriteRemoval(track) }
    }

    fun flushDeferredRemovals(onComplete: () -> Unit = {}) {
        val removals = deferredRemovals.values.toList()
        if (removals.isEmpty()) {
            onComplete()
            return
        }
        val previousChanges = removals.associate { it.id to deferredPreviousChanges.remove(it.id) }
        deferredRemovals.clear()
        val requestedKey = configurationKey
        val ids = removals.mapTo(linkedSetOf(), MusicTrack::id)
        _state.update { state ->
            state.copy(updating = state.updating + ids, deferredRemovalIds = state.deferredRemovalIds - ids)
        }
        viewModelScope.launch {
            deferredFlushMutex.withLock {
                if (removals.all { it.source == MusicSource.QQ }) {
                    flushQqDeferredRemovals(requestedKey, removals, previousChanges, ids)
                    return@withLock
                }
                removals.forEach { track ->
                    try {
                        repository.setLiked(track, false)
                        if (requestedKey == configurationKey) {
                            _state.update { it.copy(updating = it.updating - track.id) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (requestedKey == configurationKey) {
                            _state.update { state ->
                                val previous = previousChanges[track.id]
                                state.restoreFavoriteRemoval(track, previous).copy(
                                    updating = state.updating - track.id,
                                    message = error.asUserMessage(),
                                )
                            }
                        }
                    }
                }
            }
            onComplete()
        }
    }

    fun toggle(track: MusicTrack) {
        cancelQqReconcile()
        val alreadyUpdating = track.id in _state.value.updating
        val liked = !track.isQqFavorite(_state.value.ids)
        val requestedKey = configurationKey
        _state.update { state ->
            val ids = state.ids.toMutableSet().apply {
                if (liked) addAll(track.qqFavoriteIdentityIds())
                else removeAll(track.qqFavoriteIdentityIds())
            }
            state.copy(ids = ids, updating = state.updating + track.id, message = null,
                changes = state.changes + (track.id to (track to liked)))
        }
        if (alreadyUpdating) return
        viewModelScope.launch {
            var confirmed = !liked
            while (requestedKey == configurationKey) {
                val desired = track.id in _state.value.ids
                if (desired == confirmed) break
                try {
                    // 同一歌曲串行同步；请求过程中撤销只更新目标状态，不发出相互覆盖的并发请求。
                    repository.setLiked(track, desired)
                    confirmed = desired
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (requestedKey != configurationKey) return@launch
                    _state.update { state ->
                        val ids = state.ids.toMutableSet().apply {
                            if (confirmed) addAll(track.qqFavoriteIdentityIds())
                            else removeAll(track.qqFavoriteIdentityIds())
                        }
                        state.copy(ids = ids, updating = state.updating - track.id, message = error.asUserMessage(),
                            changes = state.changes + (track.id to (track to confirmed)))
                    }
                    scheduleQqReconcile(requestedKey, listOf(track))
                    return@launch
                }
            }
            if (requestedKey == configurationKey) {
                _state.update { it.copy(updating = it.updating - track.id) }
            }
        }
    }

    private suspend fun flushQqDeferredRemovals(
        requestedKey: String,
        removals: List<MusicTrack>,
        previousChanges: Map<String, Pair<MusicTrack, Boolean>?>,
        ids: Set<String>,
    ) {
        try {
            repository.setLiked(removals, false)
            if (requestedKey == configurationKey) {
                _state.update { state -> state.copy(updating = state.updating - ids) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (requestedKey == configurationKey) {
                _state.update { current ->
                    removals.fold(current) { state, track ->
                        state.restoreFavoriteRemoval(track, previousChanges[track.id])
                    }.copy(
                        updating = current.updating - ids,
                        message = error.asUserMessage(),
                    )
                }
            }
            scheduleQqReconcile(requestedKey, removals)
        }
    }

    private fun cancelQqReconcile() {
        qqReconcileJob?.cancel()
        qqReconcileJob = null
        qqReconcileTracks.clear()
    }

    private fun scheduleQqReconcile(requestedKey: String, tracks: List<MusicTrack>) {
        if (requestedKey != configurationKey) return
        val qqTracks = tracks.filter { it.source == MusicSource.QQ }
        if (qqTracks.isEmpty()) return
        qqTracks.forEach { track -> qqReconcileTracks[track.id] = track }
        qqReconcileJob?.cancel()
        qqReconcileJob = viewModelScope.launch {
            delay(QQ_FAVORITE_RECONCILE_DELAY_MS)
            if (requestedKey != configurationKey) return@launch
            val pending = qqReconcileTracks.values.toList()
            val remoteIds = try {
                repository.likedTrackIds(MusicSource.QQ)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@launch
            }
            if (requestedKey != configurationKey) return@launch
            pending.forEach { track -> qqReconcileTracks.remove(track.id) }
            _state.update { state -> state.withAuthoritativeFavorites(pending, remoteIds) }
        }
    }
}

internal fun MusicFavoriteUiState.stageFavoriteRemoval(track: MusicTrack): MusicFavoriteUiState = copy(
    ids = ids - track.qqFavoriteIdentityIds(),
    message = null,
    changes = changes + (track.id to (track to false)),
    deferredRemovalIds = deferredRemovalIds + track.id,
)

internal fun MusicFavoriteUiState.restoreFavoriteRemoval(
    track: MusicTrack,
    previousChange: Pair<MusicTrack, Boolean>?,
): MusicFavoriteUiState = copy(
    ids = ids + track.qqFavoriteIdentityIds(),
    message = null,
    // 没有旧记录时也显式写入恢复项，让已经过滤的“我的”内存快照能补回歌曲。
    changes = changes + (track.id to (previousChange ?: (track to true))),
    deferredRemovalIds = deferredRemovalIds - track.id,
)

internal fun MusicFavoriteUiState.withAuthoritativeFavorites(
    tracks: List<MusicTrack>,
    authoritativeIds: Set<String>,
): MusicFavoriteUiState {
    val settled = tracks.distinctBy(MusicTrack::id).filter { it.id !in updating }
    if (settled.isEmpty()) return this
    val reconciledIds = ids.toMutableSet()
    val reconciledChanges = changes.toMutableMap()
    settled.forEach { track ->
        val liked = track.isQqFavorite(authoritativeIds)
        if (liked) reconciledIds.addAll(track.qqFavoriteIdentityIds())
        else reconciledIds.removeAll(track.qqFavoriteIdentityIds())
        reconciledChanges[track.id] = track to liked
    }
    return copy(ids = reconciledIds, changes = reconciledChanges, message = null)
}

private const val QQ_FAVORITE_RECONCILE_DELAY_MS = 1_000L
