package com.musicone.demo

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class BiliFolderDirectoryTest {
    private val account = BiliAccountSession("fixture", "42", "用户")
    private val folder = BiliFavFolder(10, "收藏夹", 20)

    @Test fun directoryReadsCacheBeforeNetworkAndKeepsItAfterFailure() = runBlocking {
        val cache = MemoryBiliFolderCache()
        cache.saveFolders("42", listOf(folder.copy(cover = "cached-cover")))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val directory = BiliFolderDirectory(cache, this, {
            started.complete(Unit); release.await(); error("网络暂不可用")
        }, { _, _ -> "" })
        directory.attach(account)
        started.await()
        assertEquals("cached-cover", directory.state.value.folders.single().cover)
        release.complete(Unit)
        val failed = withTimeout(2_000) { directory.state.first { !it.loading } }
        assertNotNull(failed.error)
        assertEquals("cached-cover", failed.folders.single().cover)
        directory.cancel()
    }

    @Test fun refreshingDirectoryDoesNotInvalidateAVisibleFoldersCoverRequest() = runBlocking {
        val cache = MemoryBiliFolderCache()
        val coverStarted = CompletableDeferred<Unit>()
        val coverRelease = CompletableDeferred<Unit>()
        var directoryRequests = 0
        val directory = BiliFolderDirectory(cache, this, { directoryRequests++; listOf(folder) }, { _, _ ->
            coverStarted.complete(Unit); coverRelease.await(); "real-cover"
        })
        directory.attach(account)
        withTimeout(2_000) { directory.state.first { !it.loading && it.folders.isNotEmpty() } }
        directory.cover(folder)
        coverStarted.await()
        directory.refresh()
        withTimeout(2_000) { directory.state.first { !it.loading && directoryRequests == 2 } }
        coverRelease.complete(Unit)
        withTimeout(2_000) { directory.state.first { it.folders.single().cover == "real-cover" } }
        directory.cancel()
    }

    @Test fun logoutImmediatelyHidesDirectoryAndIgnoresThePreviousAccountsResult() = runBlocking {
        val cache = MemoryBiliFolderCache()
        cache.saveFolders("42", listOf(folder))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val directory = BiliFolderDirectory(cache, this, {
            started.complete(Unit); release.await(); listOf(folder)
        }, { _, _ -> "" })
        directory.attach(account)
        started.await()
        assertEquals(listOf(folder), directory.state.value.folders)
        directory.attach(null)
        release.complete(Unit)
        yield()
        assertTrue(directory.state.value.folders.isEmpty())
        assertFalse(directory.state.value.loading)
    }

    @Test fun nonemptyOldCoverIsRefreshedAndLateDirectoryMetadataCannotRestoreIt() = runBlocking {
        val cache = MemoryBiliFolderCache()
        val old = folder.copy(cover = "old-fixed-cover")
        val directory = BiliFolderDirectory(cache, this, { listOf(old) }, { _, _ -> "latest-video-cover" })
        directory.attach(account)
        withTimeout(2_000) { directory.state.first { !it.loading && it.folders.isNotEmpty() } }
        directory.cover(old)
        withTimeout(2_000) { directory.state.first { it.folders.single().cover == "latest-video-cover" } }
        directory.refresh()
        withTimeout(2_000) { directory.state.first { !it.loading } }
        assertEquals("latest-video-cover", directory.state.value.folders.single().cover)
        directory.updateCover("other-account", folder.id, "wrong-account-cover")
        assertEquals("latest-video-cover", directory.state.value.folders.single().cover)
        directory.cancel()
    }
}
