package com.musicone.demo

import kotlinx.coroutines.CancellationException

/** 合并同曲请求；无 YRC 与请求失败只短暂冷却，不写成永久确认的磁盘缓存。 */
internal class NeteaseLyricRequests(private val now: () -> Long = System::nanoTime) {
    private class Entry {
        var retryAt = Long.MIN_VALUE
        var lyrics: List<TimedLyric> = emptyList()
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var credential: String? = null

    fun load(id: String, cookie: String, failedRichRequest: () -> Boolean = { false },
        fetch: () -> List<TimedLyric>): List<TimedLyric> {
        val entry = synchronized(entries) {
            // 同一账号刷新凭据也应允许重新确认；凭据仅存内存，不进入日志或缓存键。
            if (credential != cookie) { entries.clear(); credential = cookie }
            entries[id] ?: Entry().also {
                if (entries.size >= 256) entries.remove(entries.keys.first())
                entries[id] = it
            }
        }
        return synchronized(entry) {
            if (now() < entry.retryAt) return@synchronized entry.lyrics
            try {
                val result = fetch()
                if (result.any { it.words.isNotEmpty() } || entry.lyrics.none { it.words.isNotEmpty() }) {
                    if (result.isNotEmpty()) entry.lyrics = result
                }
                entry.retryAt = now() + (if (result.isEmpty() || failedRichRequest()) 30L else 600L) * 1_000_000_000L
                entry.lyrics
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                entry.retryAt = now() + 30L * 1_000_000_000L
                if (entry.lyrics.isEmpty()) throw error
                entry.lyrics
            }
        }
    }
}
