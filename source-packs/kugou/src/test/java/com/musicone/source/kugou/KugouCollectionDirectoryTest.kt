package com.musicone.source.kugou

import com.musicone.source.sdk.SourceFetchPolicy
import com.musicone.source.sdk.SourceLibrarySection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KugouCollectionDirectoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sections = SourceLibrarySection.entries
    private fun row(id: Int) = kugouManagedCollection(JSONObject().put("listid", id)
        .put("name", "歌单$id").put("type", 0).put("list_create_userid", 42), 9, "42")!!

    @Test fun backgroundValidationNotifiesOnceOnlyWhenVisibleDirectoryChanges() = runBlocking {
        listOf(false, true).forEach { changed ->
            val folder = temporary.newFolder()
            KugouCollectionDirectory(folder).current("42", 0, {}, { listOf(row(1)) })
            val events = mutableListOf<String>()
            val cache = KugouCollectionDirectory(folder) { events += it }
            val jobs = mutableListOf<suspend () -> Unit>()
            sections.forEach { section ->
                cache.read("42", 1, section, SourceFetchPolicy.CACHE_FIRST, {},
                    { listOf(row(if (changed) 2 else 1)) }, { jobs += it })
            }
            assertEquals(1, jobs.size)
            jobs.single()()
            assertEquals(if (changed) listOf("42") else emptyList<String>(), events)
            cache.read("42", 1, sections.first(), SourceFetchPolicy.CACHE_FIRST, {}, { error("已核对") }, { jobs += it })
            assertEquals(1, jobs.size)
        }
    }

    @Test fun coldThreeSectionsAndRepeatedCacheFirstShareValidatedSessionSnapshot() = runBlocking {
        val cache = KugouCollectionDirectory(temporary.newFolder())
        var calls = 0
        val load = { calls++; listOf(row(calls)) }
        sections.forEach { section ->
            assertEquals("1", cache.read("42", 0, section, SourceFetchPolicy.CACHE_FIRST, {}, load, {}).single().listId)
        }
        assertEquals(1, calls)
        sections.forEach { section ->
            assertEquals("1", cache.read("42", 0, section, SourceFetchPolicy.CACHE_FIRST, {}, load, {}).single().listId)
        }
        assertEquals(1, calls)
    }

    @Test fun restartReturnsSnapshotImmediatelyAndSchedulesOneBackgroundValidation() = runBlocking {
        val folder = temporary.newFolder()
        KugouCollectionDirectory(folder).current("42", 0, {}, { listOf(row(1)) })
        val cache = KugouCollectionDirectory(folder)
        val jobs = mutableListOf<suspend () -> Unit>()
        var calls = 0
        sections.forEach { section ->
            val result = cache.read("42", 1, section, SourceFetchPolicy.CACHE_FIRST, {},
                { calls++; listOf(row(2)) }, { jobs += it })
            assertEquals("1", result.single().listId)
        }
        assertEquals(0, calls)
        assertEquals(1, jobs.size)
        jobs.single()()
        assertEquals("2", cache.current("42", 1, {}, { error("不能重读目录") }).single().listId)
        assertEquals(1, calls)
    }

    @Test fun overlappingRefreshBypassesDiskAndSharesResultAcrossSectionsButLaterRefreshReloads() = runBlocking {
        val folder = temporary.newFolder()
        KugouCollectionDirectory(folder).current("42", 0, {}, { listOf(row(1)) })
        val cache = KugouCollectionDirectory(folder)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val requests = sections.map { section ->
            async(start = CoroutineStart.UNDISPATCHED) {
                cache.read("42", 1, section, SourceFetchPolicy.REFRESH, {},
                    { calls++; release.await(); listOf(row(2)) }, { error("强刷不走后台") })
            }
        }
        assertEquals(1, calls)
        release.complete(Unit)
        requests.forEach { assertEquals("2", it.await().single().listId) }
        assertEquals("3", cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {},
            { calls++; listOf(row(3)) }, {}).single().listId)
        assertEquals(2, calls)
    }

    @Test fun accountSwitchNeverReadsAnotherAccountsSnapshot() = runBlocking {
        val cache = KugouCollectionDirectory(temporary.newFolder())
        cache.current("42", 0, {}, { listOf(row(1)) })
        assertTrue(cache.current("77", 1, {}, { emptyList() }).isEmpty())
        assertEquals("1", cache.current("42", 2, {}, { error("应读账号42快照") }).single().listId)
    }

    @Test fun invalidationPreventsRestartFromRestoringPreWriteDirectory() = runBlocking {
        val folder = temporary.newFolder()
        val cache = KugouCollectionDirectory(folder)
        cache.current("42", 0, {}, { listOf(row(1)) })
        cache.invalidate("42", 0)
        assertTrue(KugouCollectionDirectory(folder).current("42", 0, {}, { emptyList() }).isEmpty())
    }

    @Test fun scheduledOldBackgroundCannotOverwriteExplicitRefresh() = runBlocking {
        val folder = temporary.newFolder()
        KugouCollectionDirectory(folder).current("42", 0, {}, { listOf(row(1)) })
        val cache = KugouCollectionDirectory(folder)
        val jobs = mutableListOf<suspend () -> Unit>()
        cache.read("42", 1, sections.first(), SourceFetchPolicy.CACHE_FIRST, {}, { listOf(row(2)) }, { jobs += it })
        cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {}, { listOf(row(3)) }, {})
        jobs.single()()
        assertEquals("3", cache.current("42", 1, {}, { error("已缓存") }).single().listId)
    }

    @Test fun canceledOrChangedAccountResponseCannotBecomePersistentSnapshot() = runBlocking {
        val folder = temporary.newFolder()
        val cache = KugouCollectionDirectory(folder)
        var current = true
        try {
            cache.current("42", 0, { if (!current) throw CancellationException("换号") }, {
                current = false
                listOf(row(1))
            })
            fail("换号应取消")
        } catch (_: CancellationException) { }
        assertEquals(0, folder.listFiles().orEmpty().size)
    }

    @Test fun overlappingColdReadsShareOneNetworkRequest() = runBlocking {
        val cache = KugouCollectionDirectory(temporary.newFolder())
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val requests = sections.map { section ->
            async(start = CoroutineStart.UNDISPATCHED) {
                cache.read("42", 1, section, SourceFetchPolicy.CACHE_FIRST, {},
                    { calls++; release.await(); listOf(row(1)) }, {})
            }
        }
        assertEquals(1, calls)
        release.complete(Unit)
        requests.forEach { assertEquals("1", it.await().single().listId) }
        assertEquals(1, calls)
    }

    @Test fun invalidationRejectsInflightResponseAndDoesNotOverwriteNewSnapshot() = runBlocking {
        val folder = temporary.newFolder()
        val cache = KugouCollectionDirectory(folder)
        val release = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {},
                { release.await(); listOf(row(1)) }, {}) }
        }
        cache.invalidate("42", 1)
        cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {}, { listOf(row(2)) }, {})
        release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals("2", cache.current("42", 1, {}, { error("已有新快照") }).single().listId)
        assertEquals("2", KugouCollectionDirectory(folder).current("42", 1, {}, { error("已有新快照") }).single().listId)
    }

    @Test fun canceledNetworkOwnerDoesNotPoisonNextRefresh() = runBlocking {
        val cache = KugouCollectionDirectory(temporary.newFolder())
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {},
                { release.await(); listOf(row(1)) }, {})
        }
        owner.cancelAndJoin()
        assertEquals("2", cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {},
            { listOf(row(2)) }, {}).single().listId)
    }

    @Test fun immediateRefreshSkipsCanceledOwnerAndLateCleanupCannotRemoveNewFlight() = runBlocking {
        val cache = KugouCollectionDirectory(temporary.newFolder())
        val oldRelease = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {},
                { oldRelease.await(); listOf(row(1)) }, {})
        }
        owner.cancel()
        val newRelease = CompletableDeferred<Unit>()
        var newLoads = 0
        val refreshed = async(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {},
                { newLoads++; newRelease.await(); listOf(row(2)) }, {})
        }
        assertEquals(1, newLoads)
        yield()
        val sibling = async(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, sections.last(), SourceFetchPolicy.REFRESH, {},
                { newLoads++; listOf(row(3)) }, {})
        }
        assertEquals(1, newLoads)
        newRelease.complete(Unit)
        assertEquals("2", refreshed.await().single().listId)
        assertEquals("2", sibling.await().single().listId)
        owner.join()
    }

    @Test fun activeWaiterRetriesWhenOtherNetworkOwnerIsCanceled() = runBlocking {
        val cache = KugouCollectionDirectory(temporary.newFolder())
        val release = CompletableDeferred<Unit>()
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {},
                { release.await(); listOf(row(1)) }, {})
        }
        var waiterLoads = 0
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, sections.last(), SourceFetchPolicy.REFRESH, {},
                { waiterLoads++; listOf(row(2)) }, {})
        }
        assertEquals(0, waiterLoads)
        owner.cancelAndJoin()
        assertEquals("2", waiter.await().single().listId)
        assertEquals(1, waiterLoads)
        assertEquals("2", cache.current("42", 1, {}, { error("新目录应已缓存") }).single().listId)
    }

    @Test fun revisionChangeRejectsInflightResponseBeforePersistence() = runBlocking {
        val folder = temporary.newFolder()
        val cache = KugouCollectionDirectory(folder)
        val release = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {},
                { release.await(); listOf(row(1)) }, {}) }
        }
        assertEquals("2", cache.read("42", 2, sections.first(), SourceFetchPolicy.REFRESH, {},
            { listOf(row(2)) }, {}).single().listId)
        release.complete(Unit)
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertEquals("2", KugouCollectionDirectory(folder).current("42", 2, {}, { error("已有新快照") }).single().listId)
    }

    @Test fun backgroundFailureKeepsSnapshotButNextRefreshReportsFailure() = runBlocking {
        val folder = temporary.newFolder()
        KugouCollectionDirectory(folder).current("42", 0, {}, { listOf(row(1)) })
        val cache = KugouCollectionDirectory(folder)
        val jobs = mutableListOf<suspend () -> Unit>()
        cache.read("42", 1, sections.first(), SourceFetchPolicy.CACHE_FIRST, {},
            { error("网络失败") }, { jobs += it })
        jobs.single()()
        assertEquals("1", cache.current("42", 1, {}, { error("已有快照") }).single().listId)
        try {
            cache.read("42", 1, sections.first(), SourceFetchPolicy.REFRESH, {}, { error("网络失败") }, {})
            fail("手动刷新失败不能伪装空目录")
        } catch (failure: IllegalStateException) { assertEquals("网络失败", failure.message) }
    }
}
