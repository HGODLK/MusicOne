package com.musicone.demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 串行写入且合并等待中的进度，切歌与 seek 的调用线程只提交不可变状态引用。 */
internal class QqPlaybackWriter(
    scope: CoroutineScope,
    private val write: suspend (MusicOneUiState, Long) -> Unit,
) {
    private val pending = Channel<Pair<MusicOneUiState, Long>>(Channel.CONFLATED)
    private val worker = scope.launch {
        for ((state, position) in pending) write(state, position)
    }

    fun save(state: MusicOneUiState, positionMs: Long) { pending.trySend(state to positionMs) }
    fun close() { pending.close() }
    suspend fun join() { worker.join() }
}

internal data class QqPlaybackRestore(val state: MusicOneUiState, val positionMs: Long)

internal class QqPlaybackPersistence(context: Context) {
    private val store = QqPlaybackSnapshotStore(context)
    private val preferences = PlatformPreferences(context)
    private val lifetime = SupervisorJob()
    private val writer = QqPlaybackWriter(CoroutineScope(lifetime + Dispatchers.IO)) { state, position ->
        try {
            state.toQqPlaybackSnapshot(position)?.let(store::save)
        } catch (_: Exception) {
            Log.w("MusicPlayback", "保存播放进度失败")
        }
    }
    private var configuredSource: MusicSource? = null
    private var lastProgressBucket = -1L

    fun restore(source: MusicSource, state: MusicOneUiState): QqPlaybackRestore? {
        if (configuredSource == source) return null
        configuredSource = source
        if (source != MusicSource.QQ || state.currentTrack?.source == MusicSource.QQ) return null
        val session = preferences.readSession(MusicSource.QQ)
        val member = QqEntitlements.state.value.membership
        val snapshot = store.read()?.let { saved ->
            saved.copy(queue = saved.queue.map { it.withQqAccess(session.account?.userId.orEmpty(), member?.vip == true, member?.superVip == true) })
        }
        val restored = state.withQqPlaybackSnapshot(snapshot)
        val position = snapshot?.positionMs
            ?.coerceIn(0L, restored.currentTrack?.durationMs?.coerceAtLeast(0L) ?: 0L) ?: 0L
        resetProgressBucket(position)
        return QqPlaybackRestore(restored, position)
    }

    fun save(state: MusicOneUiState, positionMs: Long) {
        if (state.currentTrack?.source == MusicSource.QQ) writer.save(state, positionMs)
    }

    fun saveProgressIfNeeded(state: MusicOneUiState, positionMs: Long) {
        if (state.currentTrack?.source != MusicSource.QQ) return
        val bucket = positionMs / QQ_PROGRESS_PERSIST_INTERVAL_MS
        if (bucket == lastProgressBucket) return
        lastProgressBucket = bucket
        save(state, positionMs)
    }

    fun resetProgressBucket(positionMs: Long) {
        lastProgressBucket = positionMs / QQ_PROGRESS_PERSIST_INTERVAL_MS
    }

    fun close() {
        // 页面销毁后仍排空最后一次保存，不阻塞主线程，也不遗留常驻写入协程。
        writer.close()
        lifetime.complete()
    }
}

private const val QQ_PROGRESS_PERSIST_INTERVAL_MS = 5_000L
