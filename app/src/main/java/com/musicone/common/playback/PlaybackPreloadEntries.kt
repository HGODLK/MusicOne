package com.musicone

/** 只管理预加载列表里每首歌的地址缓存、下载状态和失败重试计数，不直接发网络请求。 */
internal class PlaybackPreloadEntries<K, V>(
    private val ttlMs: (K) -> Long,
    private val refreshLeadMs: Long = PLAYBACK_TICKET_REFRESH_MARGIN_MS,
) {
    data class Value<V>(val value: V, val createdMs: Long, val expiresAtMs: Long, val downloaded: Boolean)

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
    fun onForegroundResume(nowMs: Long): Set<K> {
        val invalidated = linkedSetOf<K>()
        entries.forEach { (key, entry) ->
            val ready = entry.ready
            val stale = ready == null || !fresh(ready, nowMs)
            if (stale) {
                if (ready != null) {
                    entry.refreshPending = true
                    invalidated += key
                }
                // 后台断网耗尽的重试次数，回到前台后清零，允许重新获取地址。
                if (!entry.terminal) {
                    entry.failures = 0
                    entry.nextRetryMs = 0L
                }
            }
        }
        return invalidated
    }

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
            // 地址已经过期，必须重新获取。
            expired(ready, nowMs) -> Work.RESOLVE
            entry.refreshPending -> Work.RESOLVE
            // 距离过期不到 60 秒的地址直接重新获取，不再复用。
            refreshDue(ready, nowMs) -> Work.RESOLVE
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
        return entry.refreshPending || !fresh(ready, nowMs)
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
        return ready.value.takeIf { fresh(ready, nowMs) }
    }

    @Synchronized
    fun take(key: K, nowMs: Long): V? {
        val ready = entries.remove(key)?.ready ?: return null
        return ready.value.takeIf { fresh(ready, nowMs) }
    }

    @Synchronized
    fun resolved(key: K, value: V, nowMs: Long, validForMs: Long? = null) {
        val entry = entries[key] ?: return
        // 重新拿到地址后不重置下载失败次数，避免反复换地址绕过失败上限。
        entry.ready = Value(value, nowMs, expiresAt(key, nowMs, validForMs), downloaded = false)
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

    private fun expiresAt(key: K, nowMs: Long, validForMs: Long?): Long {
        val ttl = (validForMs ?: ttlMs(key)).coerceAtLeast(0L)
        return if (nowMs > Long.MAX_VALUE - ttl) Long.MAX_VALUE else nowMs + ttl
    }

    private fun fresh(ready: Value<V>, nowMs: Long): Boolean {
        if (nowMs < ready.createdMs) return false
        val lead = refreshLeadMs.coerceAtLeast(0L)
        return nowMs < ready.expiresAtMs - lead
    }

    private fun expired(ready: Value<V>, nowMs: Long): Boolean =
        nowMs >= ready.expiresAtMs

    private fun refreshDue(ready: Value<V>, nowMs: Long): Boolean =
        !fresh(ready, nowMs)
}
