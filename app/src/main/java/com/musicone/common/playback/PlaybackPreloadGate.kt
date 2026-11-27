package com.musicone

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** 预加载的取地址和拉歌词共用一把串行错峰锁（随机延迟），文件下载在锁外并行跑，不抢当前歌曲的请求。 */
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
