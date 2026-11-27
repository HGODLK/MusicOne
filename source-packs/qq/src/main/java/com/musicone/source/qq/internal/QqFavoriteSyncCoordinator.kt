package com.musicone.source.qq.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 串行执行“我喜欢”的批量增删请求，避免并发修改互相覆盖。 */
internal class QqFavoriteSyncCoordinator {
    private val writeMutex = Mutex()
    suspend fun runWrite(block: suspend () -> Unit) { writeMutex.withLock { block() } }
}
