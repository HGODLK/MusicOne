package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class BiliFolderContentState(
    val snapshot: BiliFolderSnapshot? = null, val loading: Boolean = false, val error: String? = null,
    val retryFirst: Boolean = false,
)

internal class BiliFolderEntry internal constructor(val account: String, val folder: Long, val query: String) {
    internal val mutable = MutableStateFlow(BiliFolderContentState())
    val state = mutable.asStateFlow()
    internal val mutex = Mutex()
    internal var initialized = false
}

/** 首屏、自动分页、搜索和完整操作共用游标；迟到结果不能写入已退出的浏览会话。 */
internal class BiliFolderRepository(
    private val cache: BiliFolderCacheStore,
    private val fetch: suspend (BiliAccountSession, Long, Int, String) -> BiliFavVideoPage,
) {
    private val entries = java.util.concurrent.ConcurrentHashMap<Triple<String, Long, String>, BiliFolderEntry>()
    fun entry(account: String, folder: Long, query: String = ""): BiliFolderEntry =
        entries.getOrPut(Triple(account, folder, query.trim())) { BiliFolderEntry(account, folder, query.trim()) }
    fun clear() { entries.clear() }
    private suspend fun check(entry: BiliFolderEntry) {
        currentCoroutineContext().ensureActive()
        if (entries[Triple(entry.account, entry.folder, entry.query)] !== entry) throw CancellationException("收藏夹会话已更新")
    }

    suspend fun first(account: BiliAccountSession, entry: BiliFolderEntry, refresh: Boolean = false): BiliFolderSnapshot {
        check(entry)
        if (entry.initialized && !refresh) return requireNotNull(entry.state.value.snapshot)
        return request(entry, retryFirst = true) {
            if (!entry.initialized) {
                val cached = if (entry.query.isEmpty()) withContext(Dispatchers.IO) { cache.content(entry.account, entry.folder) } else null
                check(entry)
                if (cached != null) entry.mutable.value = entry.mutable.value.copy(snapshot = cached)
            } else if (!refresh) return@request requireNotNull(entry.state.value.snapshot)
            // 已有多页缓存保持可见，等同一范围刷新完整后一次交接，避免先缩成一页再撑开。
            val pages = entry.state.value.snapshot?.page?.coerceAtLeast(1) ?: 1
            var next: BiliFolderSnapshot? = null
            repeat(pages) {
                if (next?.hasMore != false) next = fetchNext(account, entry, next)
            }
            publish(entry, requireNotNull(next))
        }
    }

    suspend fun more(account: BiliAccountSession, entry: BiliFolderEntry): BiliFolderSnapshot {
        first(account, entry)
        return request(entry) {
            val previous = requireNotNull(entry.state.value.snapshot)
            if (!previous.hasMore) previous else publish(entry, fetchNext(account, entry, previous))
        }
    }

    suspend fun complete(account: BiliAccountSession, entry: BiliFolderEntry): BiliFolderSnapshot {
        first(account, entry)
        return request(entry) {
            var result = requireNotNull(entry.state.value.snapshot)
            while (result.hasMore) result = publish(entry, fetchNext(account, entry, result))
            result
        }
    }

    private suspend fun fetchNext(account: BiliAccountSession, entry: BiliFolderEntry,
        previous: BiliFolderSnapshot?): BiliFolderSnapshot {
        check(entry)
        require(account.mid == entry.account) { "收藏夹账号已变化" }
        val page = (previous?.page ?: 0) + 1
        val result = withContext(Dispatchers.IO) { fetch(account, entry.folder, page, entry.query) }
        check(entry)
        return mergeBiliFolderPage(previous, result, page)
    }

    private suspend fun publish(entry: BiliFolderEntry, snapshot: BiliFolderSnapshot): BiliFolderSnapshot {
        check(entry)
        entry.initialized = true
        entry.mutable.value = entry.mutable.value.copy(snapshot = snapshot)
        // 缓存写入失败不把已经成功取得的网络列表变成加载失败。
        if (entry.query.isEmpty()) withContext(Dispatchers.IO) {
            try { cache.saveContent(entry.account, entry.folder, snapshot) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        check(entry)
        return snapshot
    }

    private suspend fun request(entry: BiliFolderEntry, retryFirst: Boolean = false,
        action: suspend () -> BiliFolderSnapshot): BiliFolderSnapshot =
        entry.mutex.withLock {
            check(entry)
            entry.mutable.value = entry.mutable.value.copy(loading = true, error = null)
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                check(entry)
                entry.mutable.value = entry.mutable.value.copy(error = failure.asUserMessage(), retryFirst = retryFirst)
                throw failure
            } finally { entry.mutable.value = entry.mutable.value.copy(loading = false) }
        }
}

internal fun mergeBiliFolderPage(previous: BiliFolderSnapshot?, incoming: BiliFavVideoPage, page: Int): BiliFolderSnapshot {
    val videos = (previous?.videos.orEmpty() + incoming.videos).distinctBy { it.bvid to it.aid }
    require(!incoming.hasMore || videos.size > previous?.videos.orEmpty().size) { "收藏夹分页未前进，请重试" }
    return BiliFolderSnapshot(videos, page, incoming.hasMore)
}
