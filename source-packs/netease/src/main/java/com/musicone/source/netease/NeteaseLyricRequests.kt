package com.musicone.source.netease

import kotlinx.coroutines.CancellationException

/** 合并同一首歌的并发歌词请求；没拿到逐字歌词或请求失败时只做短时间冷却，不永久缓存。 */
internal class NeteaseLyricRequests(private val now: () -> Long = System::nanoTime) {
    private class Entry {
        var retryAt = Long.MIN_VALUE
        var lyrics: List<NeteaseLyricLine> = emptyList()
    }

    private val entries = LinkedHashMap<String, Entry>()
    private var credential: String? = null

    fun load(id: String, cookie: String, failedRichRequest: () -> Boolean = { false },
        fetch: () -> List<NeteaseLyricLine>): List<NeteaseLyricLine> {
        val entry = synchronized(entries) {
            // 账号 Cookie 变了就清空内存冷却缓存，允许重新拉一次（Cookie 只放内存，不进日志也不当缓存 key）
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
