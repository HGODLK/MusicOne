package com.musicone.source.netease

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** 每个页面区域独立加载；旧请求不能覆盖新结果，也不能清除新请求的加载状态。 */
internal class NeteasePageLoad<T>(private val initial: T) {
    var value by mutableStateOf(initial)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var generation = 0L
    private var requestRevision = -1L
    private var task: Job? = null

    fun start(scope: CoroutineScope, revision: Long, currentRevision: () -> Long, load: suspend () -> T): Job {
        task?.takeIf { it.isActive && requestRevision == revision }?.let { return it }
        val token = ++generation
        task?.cancel()
        requestRevision = revision
        loading = true
        error = null
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = load()
                ensureActive()
                if (generation == token && currentRevision() == revision) value = result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == token && currentRevision() == revision) error = failure.message ?: "加载失败，请重试"
            } finally {
                if (generation == token) loading = false
            }
        }
        task = next
        next.start()
        return next
    }

    fun clear() {
        generation++
        task?.cancel()
        task = null
        value = initial
        loading = false
        error = null
    }
}
