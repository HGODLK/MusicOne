package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 同一个账号的预加载和进页面刷新复用同一个请求，失败时保留现有收藏列表。 */
internal class MusicFavoriteRefresh(private val scope: CoroutineScope) {
    private var job: Job? = null
    private val mutations = mutableMapOf<String, Long>()
    fun markChanged(id: String) { mutations[id] = (mutations[id] ?: 0L) + 1L }
    fun snapshot() = mutations.toMap()
    fun changedSince(started: Map<String, Long>) = mutations.keys.filterTo(mutableSetOf()) { mutations[it] != started[it] }
    fun cancel() { job?.cancel(); job = null; mutations.clear() }
    fun request(read: suspend () -> Set<String>, apply: (Set<String>) -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            val ids = try { read() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Throwable) { return@launch }
            apply(ids)
        }
    }
}

/** 每次起播递增版本号，哪怕重播同一首歌也会重新核对红心状态。 */
internal class MusicFavoritePreparationTargets {
    private val mutableTarget = MutableStateFlow<Pair<MusicTrack, Long>?>(null)
    val target = mutableTarget.asStateFlow()
    fun prepare(track: MusicTrack, generation: Long) { mutableTarget.value = track to generation }
}

internal fun MusicFavoriteUiState.withRefreshedFavorites(remote: Set<String>, started: MusicFavoriteUiState,
    changedIds: Set<String> = emptySet()): MusicFavoriteUiState {
    val protected = updating + deferredRemovalIds + started.updating + started.deferredRemovalIds +
        changes.keys.filter { changes[it] != started.changes[it] } + changedIds
    val pending = changes.filterKeys { it in protected }
    val merged = remote.toMutableSet()
    pending.values.forEach { (track, liked) ->
        if (liked) merged.addAll(playlistTrackIdentities(track)) else merged.removeAll(playlistTrackIdentities(track))
    }
    // 云端已经和本地操作对齐的记录直接清掉，以后以云端最新状态为准。
    return copy(ids = merged, changes = pending)
}
