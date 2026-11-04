package com.musicone.source.kugou

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 只保存一次最近播放打开意图；读取和导航仍由现有控制器及公共入口完成。 */
internal class KugouRecentOpenRequest(
    private val scope: CoroutineScope,
    private val owner: () -> KugouHomeOwner,
    private val state: () -> KugouHomeState,
    private val read: () -> Job,
) {
    private var available = false
    private var pending: Job? = null
    private var generation = 0L

    fun setAvailable(value: Boolean) {
        available = value
        if (!value) {
            generation++
            pending?.cancel()
            pending = null
        }
    }

    fun open(onLoaded: (KugouHomeState) -> Unit): Job? {
        if (!available) return null
        pending?.takeIf { it.isActive }?.let { return it }
        val expected = owner()
        if (expected.accountId == null) return null
        val initial = state()
        if (initial.owner == expected && initial.recentLoaded) {
            onLoaded(initial)
            return null
        }
        val version = ++generation
        pending = scope.launch {
            var request = read()
            while (true) {
                request.join()
                currentCoroutineContext().ensureActive()
                val result = state()
                if (!available || generation != version || owner() != expected || result.owner != expected) return@launch
                // 读取期间的新上报会产生后继核验，打开意图继续等待最新一次读取。
                if (result.loadingRecent) {
                    request = read()
                    continue
                }
                if (result.recentLoaded && result.recentError == null) onLoaded(result)
                return@launch
            }
        }
        return pending
    }
}
