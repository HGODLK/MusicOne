package com.musicone.demo

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** QQ“我喜欢”属于同一个云端歌单，所有写操作必须共用一条串行通道。 */
internal class QqFavoriteSyncCoordinator {
    private val writeMutex = Mutex()

    suspend fun runWrite(block: suspend () -> Unit) {
        writeMutex.withLock { block() }
    }
}
