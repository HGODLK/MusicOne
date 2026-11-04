package com.musicone.source.netease

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NeteasePageLoadTest {
    @Test fun startupAccountRefreshDoesNotDiscardHomeOrLibrary() = runBlocking {
        val revision = NeteaseAccountRevision()
        val home = NeteasePageLoad(emptyList<String>())
        val library = NeteasePageLoad(emptyList<String>())
        val response = CompletableDeferred<List<String>>()
        var calls = 0
        val homeJob = home.start(this, revision.get(), revision::get) { calls++; response.await() }
        val libraryJob = library.start(this, revision.get(), revision::get) { response.await() }
        assertSame(homeJob, home.start(this, revision.get(), revision::get) { error("重复触发不应另发请求") })
        yield()
        revision.saved("123", "123", refreshing = true, wasExpired = false)
        response.complete(listOf("已返回"))
        joinAll(homeJob, libraryJob)
        assertEquals(1, calls)
        assertEquals(listOf("已返回"), home.value)
        assertEquals(home.value, library.value)
        assertFalse(home.loading)
        assertFalse(library.loading)
    }

    @Test fun slowSongsDoNotBlockPlaylistsAndFailureCanRetry() = runBlocking {
        val playlists = NeteasePageLoad(emptyList<String>())
        val tracks = NeteasePageLoad(emptyList<String>())
        val songs = CompletableDeferred<Unit>()
        val trackJob = tracks.start(this, 0, { 0 }) { songs.await(); throw java.io.IOException("歌曲暂时失败") }
        playlists.start(this, 0, { 0 }) { listOf("推荐歌单") }.join()
        assertEquals(listOf("推荐歌单"), playlists.value)
        assertTrue(tracks.loading)
        songs.complete(Unit)
        trackJob.join()
        assertFalse(tracks.loading)
        assertEquals("歌曲暂时失败", tracks.error)
        tracks.start(this, 0, { 0 }) { listOf("歌曲") }.join()
        assertEquals(listOf("歌曲"), tracks.value)
        assertNull(tracks.error)
    }

    @Test fun replacedRequestCannotClearNewLoadingOrPublishOldAccount() = runBlocking {
        val state = NeteasePageLoad(emptyList<String>())
        var revision = 0L
        val oldResponse = CompletableDeferred<List<String>>()
        val newResponse = CompletableDeferred<List<String>>()
        val old = state.start(this, revision, { revision }) { withContext(NonCancellable) { oldResponse.await() } }
        yield()
        revision++
        state.clear()
        val next = state.start(this, revision, { revision }) { newResponse.await() }
        yield()
        oldResponse.complete(listOf("旧账号"))
        old.join()
        assertTrue(state.loading)
        assertTrue(state.value.isEmpty())
        newResponse.complete(listOf("新账号"))
        next.join()
        assertEquals(listOf("新账号"), state.value)
        assertFalse(state.loading)
    }

    @Test fun cancellationAndRevisionMismatchBothEndLoading() = runBlocking {
        val state = NeteasePageLoad(emptyList<String>())
        val task = state.start(this, 0, { 0 }) { awaitCancellation() }
        yield()
        task.cancelAndJoin()
        assertFalse(state.loading)
        assertNull(state.error)
        state.start(this, 0, { 1 }) { listOf("过期结果") }.join()
        assertTrue(state.value.isEmpty())
        assertFalse(state.loading)
    }

    @Test fun explicitLoginAccountSwitchAndExpiredRecoveryInvalidateRequests() {
        val revision = NeteaseAccountRevision()
        revision.saved("123", "123", refreshing = true, wasExpired = false)
        assertEquals(0L, revision.get())
        revision.saved("123", "123", refreshing = false, wasExpired = false)
        assertEquals(1L, revision.get())
        revision.saved("123", "456", refreshing = true, wasExpired = false)
        assertEquals(2L, revision.get())
        revision.saved("456", "456", refreshing = true, wasExpired = true)
        assertEquals(3L, revision.get())
        revision.incrementAndGet()
        assertEquals(4L, revision.get())
    }
}
