package com.musicone.demo

/** 只管理预加载窗口内的票据、下载完成状态及失败退避，不执行网络工作。 */
internal class PlaybackPreloadEntries<K, V>(
    private val fresh: (Long, Long) -> Boolean,
    private val refreshDue: (Long, Long) -> Boolean,
) {
    data class Value<V>(val value: V, val createdMs: Long, val downloaded: Boolean)

    enum class Work { NONE, RESOLVE, DOWNLOAD }

    private class Entry<V> {
        var ready: Value<V>? = null
        var failures: Int = 0
        var nextRetryMs: Long = 0L
        var terminal: Boolean = false
        var refreshPending: Boolean = false
    }

    private val entries = mutableMapOf<K, Entry<V>>()

    @Synchronized
    fun retain(keys: Set<K>) {
        entries.keys.retainAll(keys)
    }

    @Synchronized
    fun work(key: K, nowMs: Long, allowRefresh: Boolean = true): Work {
        val entry = entries.getOrPut(key) { Entry() }
        if (entry.terminal || entry.failures >= 3 || nowMs < entry.nextRetryMs) return Work.NONE
        val ready = entry.ready
        val work = when {
            ready == null -> Work.RESOLVE
            entry.refreshPending -> Work.RESOLVE
            (allowRefresh || entry.failures > 0) && refreshDue(ready.createdMs, nowMs) -> Work.RESOLVE
            !ready.downloaded && !fresh(ready.createdMs, nowMs) -> Work.RESOLVE
            !ready.downloaded -> Work.DOWNLOAD
            else -> Work.NONE
        }
        if (work == Work.RESOLVE) entry.refreshPending = true
        return work
    }

    @Synchronized
    fun needsRefresh(key: K, nowMs: Long, allowRefresh: Boolean): Boolean {
        val entry = entries[key] ?: return false
        if (entry.terminal || entry.failures >= 3 || nowMs < entry.nextRetryMs) return false
        val ready = entry.ready ?: return false
        return entry.refreshPending || (allowRefresh && refreshDue(ready.createdMs, nowMs))
    }

    @Synchronized
    fun blocked(key: K, nowMs: Long) {
        val entry = entries[key] ?: return
        val ready = entry.ready ?: return
        entry.ready = ready.copy(downloaded = false)
        entry.nextRetryMs = if (nowMs > Long.MAX_VALUE - 10_000L) Long.MAX_VALUE else nowMs + 10_000L
    }

    @Synchronized
    fun peek(key: K, nowMs: Long): V? {
        val ready = entries[key]?.ready ?: return null
        return ready.value.takeIf { fresh(ready.createdMs, nowMs) }
    }

    @Synchronized
    fun take(key: K, nowMs: Long): V? {
        val ready = entries.remove(key)?.ready ?: return null
        return ready.value.takeIf { fresh(ready.createdMs, nowMs) }
    }

    @Synchronized
    fun resolved(key: K, value: V, nowMs: Long) {
        val entry = entries[key] ?: return
        // 解析成功仍沿用下载失败预算，避免重复换票据绕过失败上限。
        entry.ready = Value(value, nowMs, downloaded = false)
        entry.refreshPending = false
    }

    @Synchronized
    fun downloaded(key: K) {
        val entry = entries[key] ?: return
        val ready = entry.ready ?: return
        entry.ready = ready.copy(downloaded = true)
        entry.failures = 0
        entry.nextRetryMs = 0L
    }

    @Synchronized
    fun failed(key: K, nowMs: Long, retryable: Boolean) {
        val entry = entries[key] ?: return
        entry.failures = (entry.failures + 1).coerceAtMost(3)
        if (!retryable) entry.terminal = true
        val delayMs = if (entry.failures == 1) 10_000L else 30_000L
        entry.nextRetryMs = if (nowMs > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else nowMs + delayMs
    }
}
