package com.musicone.demo

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** 音频取源与歌词请求共用单路错峰，下载在锁外并行，不阻塞当前歌曲解析。 */
internal class PlaybackPreloadGate(
    private val delayMs: () -> Long = { Random.nextLong(300L, 5_001L) },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val mayStart: () -> Boolean = { true },
) {
    private val mutex = Mutex()
    suspend fun <T> request(block: suspend () -> T): T = mutex.withLock {
        wait(delayMs())
        while (!mayStart()) wait(250L)
        block()
    }
}
