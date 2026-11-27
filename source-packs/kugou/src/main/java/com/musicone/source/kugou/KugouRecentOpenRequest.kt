package com.musicone.source.kugou

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 暂存用户点击“最近播放”的打开请求，等数据拉完后再跳转。 */
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
                // 如果正在拉取期间又触发了新一轮刷新，继续等最新这轮拉完再打开
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
