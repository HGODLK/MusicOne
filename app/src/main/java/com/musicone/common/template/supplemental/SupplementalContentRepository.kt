package com.musicone

import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class SupplementalContentSnapshot(val contents: List<SourceContent>, val cursor: String?, val page: Int,
    val visitedCursors: Set<String> = emptySet()) {
    val hasMore get() = cursor != null
}
internal data class SupplementalContentState(val snapshot: SupplementalContentSnapshot? = null,
    val loading: Boolean = false, val error: String? = null, val retryFirst: Boolean = true)
internal class SupplementalContentEntry(val collection: SourceRemoteCollection, val query: String,
    val accountRevision: Long) {
    internal val mutable = MutableStateFlow(SupplementalContentState())
    val state = mutable.asStateFlow()
    internal val mutex = Mutex()
}

/** 游标只由音源解释；刷新失败保留已显示的多页内容与原重试位置。 */
internal class SupplementalContentRepository(private val source: SourceRemoteCollections,
    private val revision: () -> Long) {
    private val entries = mutableMapOf<Triple<Long, String, String>, SupplementalContentEntry>()
    fun entry(collection: SourceRemoteCollection, query: String): SupplementalContentEntry =
        entries.getOrPut(Triple(revision(), collection.identity.remoteId, query)) {
            SupplementalContentEntry(collection, query, revision())
        }
    private fun check(entry: SupplementalContentEntry) {
        if (entry.accountRevision != revision()) throw CancellationException("账号已改变")
    }
    private suspend fun page(entry: SupplementalContentEntry, cursor: String?, refresh: Boolean = false): SourcePage<SourceContent> {
        check(entry)
        val result = source.contents(entry.collection, entry.query.takeIf(String::isNotBlank), SourceCursorRequest(cursor, policy = if (refresh) SourceFetchPolicy.REFRESH else SourceFetchPolicy.CACHE_FIRST))
        currentCoroutineContext().ensureActive(); check(entry)
        require(result.nextCursor == null || result.nextCursor != cursor) { "音源返回了重复分页游标" }
        return result
    }
    suspend fun first(entry: SupplementalContentEntry, refresh: Boolean = false): SupplementalContentSnapshot = entry.mutex.withLock {
        val old = entry.mutable.value.snapshot
        if (!refresh && old != null) return@withLock old
        attempt(entry, true) {
            var snapshot: SupplementalContentSnapshot? = null
            val seen = hashSetOf<String>()
            repeat(old?.page ?: 1) {
                if (snapshot != null && !snapshot!!.hasMore) return@repeat
                val next = page(entry, snapshot?.cursor, refresh)
                next.nextCursor?.let { require(seen.add(it)) { "音源分页游标形成循环" } }
                snapshot = merge(snapshot, next)
            }
            checkNotNull(snapshot)
        }
    }
    suspend fun more(entry: SupplementalContentEntry): SupplementalContentSnapshot = entry.mutex.withLock {
        val old = entry.mutable.value.snapshot
        if (old != null && !old.hasMore) return@withLock old
        attempt(entry, old == null) { merge(old, page(entry, old?.cursor)) }
    }
    suspend fun complete(entry: SupplementalContentEntry): SupplementalContentSnapshot = entry.mutex.withLock {
        attempt(entry, entry.mutable.value.snapshot == null) {
            var snapshot = entry.mutable.value.snapshot
            val seen = hashSetOf<String>()
            do {
                if (snapshot != null && !snapshot.hasMore) break
                snapshot?.cursor?.let { require(seen.add(it)) { "音源分页游标形成循环" } }
                snapshot = merge(snapshot, page(entry, snapshot?.cursor))
            } while (snapshot.hasMore)
            checkNotNull(snapshot)
        }
    }
    private fun merge(old: SupplementalContentSnapshot?, next: SourcePage<SourceContent>): SupplementalContentSnapshot {
        val visited = old?.visitedCursors.orEmpty()
        require(next.nextCursor == null || next.nextCursor !in visited) { "音源分页游标形成循环" }
        return SupplementalContentSnapshot((old?.contents.orEmpty() + next.items).distinctBy { it.identity }, next.nextCursor,
            (old?.page ?: 0) + 1, visited + listOfNotNull(next.nextCursor))
    }
    private suspend fun attempt(entry: SupplementalContentEntry, first: Boolean,
        block: suspend () -> SupplementalContentSnapshot): SupplementalContentSnapshot {
        check(entry)
        entry.mutable.value = entry.mutable.value.copy(loading = true, error = null)
        try {
            val value = block(); check(entry)
            entry.mutable.value = SupplementalContentState(value)
            return value
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            entry.mutable.value = entry.mutable.value.copy(error = error.message ?: "读取内容失败", retryFirst = first)
            throw error
        } finally { entry.mutable.value = entry.mutable.value.copy(loading = false) }
    }
}
