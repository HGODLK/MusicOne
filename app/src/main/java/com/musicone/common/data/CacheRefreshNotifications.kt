package com.musicone

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 缓存管理页监听所有缓存变动，而离线可播状态只监听音频变动，避免下个封面就触发全库重扫。 */
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
            // 如果批处理刚好结束就重新循环判断，避免漏发或把标记带到下一批。
            if (audioRemovalBatch.compareAndSet(batch, 2)) return
        }
    }

    fun finishAudioRemovalBatch() {
        if (audioRemovalBatch.getAndSet(0) == 2) audioChanged() else cacheChanged()
    }
}
