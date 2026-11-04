package com.musicone

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 管理页关注所有缓存变化；离线可用性只关注音频，不因图片或歌单引用重新扫库。 */
internal class CacheRefreshNotifications {
    private val cacheChanges = MutableStateFlow(0L)
    private val audioChanges = MutableStateFlow(0L)
    // 0 为批次外，1 为批次内未删音频，2 为批次内已删音频。
    private val audioRemovalBatch = AtomicInteger(0)
    val cacheRevision = cacheChanges.asStateFlow()
    val audioAvailabilityRevision = audioChanges.asStateFlow()

    fun cacheChanged() { cacheChanges.update { it + 1 } }

    fun audioChanged() {
        audioChanges.update { it + 1 }
        cacheChanged()
    }

    fun beginAudioRemovalBatch() { audioRemovalBatch.set(1) }

    fun audioRemoved() {
        while (true) {
            val batch = audioRemovalBatch.get()
            if (batch == 0) {
                audioChanged()
                return
            }
            // 批次已结束则重试，不能把结束后到达的删除标记遗留到下一批。
            if (audioRemovalBatch.compareAndSet(batch, 2)) return
        }
    }

    fun finishAudioRemovalBatch() {
        if (audioRemovalBatch.getAndSet(0) == 2) audioChanged() else cacheChanged()
    }
}
