package com.musicone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 串行写入且合并等待中的进度，切歌与 seek 的调用线程只提交不可变状态引用。 */
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
