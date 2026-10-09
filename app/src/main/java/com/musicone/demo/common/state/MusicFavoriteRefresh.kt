package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 同一账号的预加载和入页刷新共用在途读取，失败保留已有状态。 */
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

/** 每次真正起播都发布代次，重复播放同一首也会重新校验喜欢状态。 */
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
        if (liked) merged.addAll(track.qqFavoriteIdentityIds()) else merged.removeAll(track.qqFavoriteIdentityIds())
    }
    // 已结算的旧本地覆盖项退出，让其他设备上的修改能成为下一轮权威状态。
    return copy(ids = merged, changes = pending)
}
