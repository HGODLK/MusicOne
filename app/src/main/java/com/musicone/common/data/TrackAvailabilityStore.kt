package com.musicone

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

/** 歌曲可播状态按账号版本隔离；同一首歌并发核对会合并去重，核对不出结果时恢复为可点击重试。 */
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
            // 列表接口带的历史不可播标记只当成待核对，不覆盖已经确认过的结果。
            if (track.id !in snapshot.unavailable && key !in pending) return
            val version = versions[track.id] ?: 0L
            pending[key] ?: scope.async(start = CoroutineStart.LAZY) {
                // 先清掉旧的不可播标记，防止本次核对超时或失败时一直把歌锁死点不了。
                synchronized(pending) {
                    if ((versions[track.id] ?: 0L) == version) publish(track, snapshot.revision, null)
                }
                val reason = try { verify(track) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                synchronized(pending) {
                    // 如果期间这首歌已经实际播起来了，版本号会变，迟到的核对结果不能再把它置灰。
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

/** 综合计算歌曲在界面上是否可点：音源可用且有完整离线缓存优先可播，不盲信列表里的旧不可播标记。 */
internal fun displayedTrackAvailability(track: MusicTrack, reason: String?, offline: Boolean, online: Boolean = true,
    sourceStatus: QueueSourceStatus = QueueSourceStatus.AVAILABLE): MusicTrack {
    if (track.source == MusicSource.LOCAL) return track
    // 音源包缺失或被用户标记删除时优先级高于离线缓存，这里只改展示副本，不写回原始数据。
    if (sourceStatus != QueueSourceStatus.AVAILABLE && sourceStatus != QueueSourceStatus.INITIALIZING) {
        return track.copy(playable = false, unavailableReason = sourceStatus.message(track.source))
    }
    if (!online && track.source != MusicSource.LOCAL) return track.copy(
        playable = offline, unavailableReason = if (offline) null else "这首歌曲尚未完整缓存，请联网后缓存",
    )
    return track.copy(playable = offline || reason == null, unavailableReason = reason.takeUnless { offline })
}
