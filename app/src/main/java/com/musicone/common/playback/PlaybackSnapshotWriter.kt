package com.musicone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 后台串行保存播放状态，连续的进度更新会自动合并，避免频繁写盘阻塞主线程。 */
internal class PlaybackSnapshotWriter(
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

internal data class PlaybackRestore(val state: MusicOneUiState, val positionMs: Long)
