package com.musicone.source.qq.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** QQ 我喜欢的批量写操作共用串行通道，保留云端记录删除语义。 */
internal class QqFavoriteSyncCoordinator {
    private val writeMutex = Mutex()
    suspend fun runWrite(block: suspend () -> Unit) { writeMutex.withLock { block() } }
}
