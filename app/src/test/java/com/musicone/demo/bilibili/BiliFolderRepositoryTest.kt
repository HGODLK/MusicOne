package com.musicone.demo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

internal class MemoryBiliFolderCache : BiliFolderCacheStore {
    val directories = java.util.concurrent.ConcurrentHashMap<String, List<BiliFavFolder>>()
    val snapshots = java.util.concurrent.ConcurrentHashMap<Pair<String, Long>, BiliFolderSnapshot>()
    override fun folders(account: String) = directories[account].orEmpty()
    override fun saveFolders(account: String, folders: List<BiliFavFolder>) { directories[account] = folders }
    override fun content(account: String, folder: Long) = snapshots[account to folder]
    override fun saveContent(account: String, folder: Long, content: BiliFolderSnapshot) { snapshots[account to folder] = content }
}

class BiliFolderRepositoryTest {
    private val account = BiliAccountSession("fixture", "42", "用户")
    private fun video(id: Int) = BiliVideoItem(id.toLong(), "BV$id", 0, "视频$id", "https://example.test/$id.jpg", "作者", 60)

    @Test fun cachedRangeStaysVisibleUntilEveryPreviouslyLoadedPageRefreshes() = runBlocking {
        val cache = MemoryBiliFolderCache()
        val old = BiliFolderSnapshot(listOf(video(8), video(9)), 2, true)
        cache.saveContent("42", 10, old)
        val second = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = BiliFolderRepository(cache) { _, _, page, _ ->
            if (page == 2) { second.complete(Unit); release.await() }
            BiliFavVideoPage(listOf(video(page)), true)
        }
        val entry = repository.entry("42", 10)
        val load = async { repository.first(account, entry) }
        second.await()
        assertEquals(old, entry.state.value.snapshot)
        assertEquals(old, cache.content("42", 10))
        assertTrue(entry.state.value.loading)
        release.complete(Unit)
        assertEquals(listOf(video(1), video(2)), load.await().videos)
        assertEquals(entry.state.value.snapshot, cache.content("42", 10))
        assertFalse(entry.state.value.loading)
    }

    @Test fun failedRefreshRetainsCacheAndCanRetryTheFirstPage() = runBlocking {
        val cache = MemoryBiliFolderCache()
        val old = BiliFolderSnapshot(listOf(video(9)), 1, false)
        cache.saveContent("42", 10, old)
        var failed = true
        val repository = BiliFolderRepository(cache) { _, _, _, _ ->
            if (failed) throw IllegalStateException("网络暂不可用")
            BiliFavVideoPage(listOf(video(1)), false)
        }
        val entry = repository.entry("42", 10)
        try { repository.first(account, entry); fail() } catch (_: IllegalStateException) { }
        assertEquals(old, entry.state.value.snapshot)
        assertFalse(entry.state.value.loading)
        assertTrue(entry.state.value.retryFirst)
        assertNotNull(entry.state.value.error)
        failed = false
        assertEquals(listOf(video(1)), repository.first(account, entry, refresh = true).videos)
        assertNull(entry.state.value.error)
    }

    @Test fun browsingAndSimultaneousCompleteOperationsNeverRequestTheSamePageTwice() = runBlocking {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val repository = BiliFolderRepository(MemoryBiliFolderCache()) { _, _, page, _ ->
            calls += page
            delay(5)
            BiliFavVideoPage(listOf(video(page)), page < 3)
        }
        val entry = repository.entry("42", 10)
        repository.first(account, entry)
        val results = awaitAll(async { repository.complete(account, entry) }, async { repository.complete(account, entry) },
            async { repository.more(account, entry) })
        assertEquals(listOf(1, 2, 3), calls)
        results.forEach { assertEquals(listOf(video(1), video(2), video(3)), it.videos) }
    }

    @Test fun queryAndAccountAreIsolatedAndSearchAllLoadsEveryMatchingPage() = runBlocking {
        val cache = MemoryBiliFolderCache()
        val normal = BiliFolderSnapshot(listOf(video(9)), 1, false)
        cache.saveContent("42", 10, normal)
        val calls = mutableListOf<String>()
        val repository = BiliFolderRepository(cache) { session, _, page, query ->
            calls += "${session.mid}:$query:$page"
            BiliFavVideoPage(listOf(video(page)), page < 2)
        }
        val search = repository.entry("42", 10, "  歌曲  ")
        assertEquals(listOf(video(1), video(2)), repository.complete(account, search).videos)
        assertEquals(listOf("42:歌曲:1", "42:歌曲:2"), calls)
        assertEquals(normal, cache.content("42", 10))
        assertNull(repository.entry("43", 10).state.value.snapshot)
        assertNotSame(search, repository.entry("42", 10))
    }

    @Test fun duplicatePageStopsAutomaticProgressAndRetainsRetryCursor() = runBlocking {
        var repeated = true
        val repository = BiliFolderRepository(MemoryBiliFolderCache()) { _, _, page, _ ->
            BiliFavVideoPage(listOf(video(if (page == 1 || repeated) 1 else 2)), page == 1 || repeated)
        }
        val entry = repository.entry("42", 10)
        repository.first(account, entry)
        try { repository.more(account, entry); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, entry.state.value.snapshot?.page)
        assertFalse(entry.state.value.loading)
        assertFalse(entry.state.value.retryFirst)
        assertNotNull(entry.state.value.error)
        repeated = false
        assertEquals(listOf(video(1), video(2)), repository.more(account, entry).videos)
    }

    @Test fun leavingTheAccountRejectsLateNetworkResultsAndDoesNotOverwriteCache() = runBlocking {
        val cache = MemoryBiliFolderCache()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = BiliFolderRepository(cache) { _, _, _, _ ->
            started.complete(Unit); release.await()
            BiliFavVideoPage(listOf(video(1)), false)
        }
        val entry = repository.entry("42", 10)
        val load = async { repository.first(account, entry) }
        started.await()
        repository.clear()
        release.complete(Unit)
        try { load.await(); fail() } catch (_: CancellationException) { }
        assertNull(entry.state.value.snapshot)
        assertNull(cache.content("42", 10))
        assertFalse(entry.state.value.loading)
    }
}
