package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class TrackAvailabilitySnapshot(
    val revision: Long = 0L,
    val unavailable: Map<String, String> = emptyMap(),
)

/** 确认结果按账号版本隔离；重新展示时合并同曲核对，未知结果恢复可重试。 */
internal class TrackAvailabilityStore(
    private val scope: CoroutineScope,
    private val verify: suspend (MusicTrack) -> String?,
) {
    private val mutable = MutableStateFlow(TrackAvailabilitySnapshot())
    val state = mutable.asStateFlow()
    private val pending = mutableMapOf<Pair<Long, String>, Deferred<Unit>>()
    private val versions = mutableMapOf<String, Long>()

    fun reset() {
        mutable.update { TrackAvailabilitySnapshot(revision = it.revision + 1L) }
    }

    fun report(track: MusicTrack, revision: Long, reason: String?) = synchronized(pending) {
        if (state.value.revision != revision) return@synchronized
        versions[track.id] = (versions[track.id] ?: 0L) + 1L
        publish(track, revision, reason)
    }

    private fun publish(track: MusicTrack, revision: Long, reason: String?) {
        mutable.update { old ->
            if (old.revision != revision) old else old.copy(unavailable =
                if (reason == null) old.unavailable - track.id else old.unavailable + (track.id to reason))
        }
    }

    suspend fun refresh(track: MusicTrack) {
        val request = synchronized(pending) {
            val snapshot = state.value
            val key = snapshot.revision to track.id
            // 列表里的历史标志只作为未知，避免覆盖已经取到音源的实际结果。
            if (track.id !in snapshot.unavailable && key !in pending) return
            val version = versions[track.id] ?: 0L
            pending[key] ?: scope.async(start = CoroutineStart.LAZY) {
                // 旧结果不能在本次核对未知或失败时继续锁死点击。
                synchronized(pending) {
                    if ((versions[track.id] ?: 0L) == version) publish(track, snapshot.revision, null)
                }
                val reason = try { verify(track) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                synchronized(pending) {
                    // 后发生的实际播放结果优先，迟到核对不能再次把成功播放的歌曲置灰。
                    if ((versions[track.id] ?: 0L) == version) publish(track, snapshot.revision, reason)
                }
            }.also { job ->
                pending[key] = job
                job.invokeOnCompletion { synchronized(pending) {
                    if (pending[key] === job) pending.remove(key)
                } }
            }
        }
        request.await()
    }
}

/** 在线列表中的历史标志不能代替本轮确认，完整离线音源仍优先。 */
internal fun displayedTrackAvailability(track: MusicTrack, reason: String?, offline: Boolean, online: Boolean = true): MusicTrack {
    if (!online && track.source != MusicSource.LOCAL) return track.copy(
        playable = offline, unavailableReason = if (offline) null else "这首歌曲尚未完整缓存，请联网后缓存",
    )
    if (track.source !in setOf(MusicSource.QQ, MusicSource.KUGOU, MusicSource.NETEASE)) return track
    return track.copy(playable = offline || reason == null, unavailableReason = reason.takeUnless { offline })
}
