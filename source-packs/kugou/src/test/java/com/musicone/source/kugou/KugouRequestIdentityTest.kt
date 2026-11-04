package com.musicone.source.kugou

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class KugouRequestIdentityTest {
    @Test fun cancelledRequestCanFinishEvenWhenItsCoroutineOrAccountIsNoLongerCurrent() {
        val requests = KugouRequestIdentity()
        val token = requests.begin("tracks")
        var loading = true
        val coroutineActive = false
        val accountCurrent = false
        assertFalse(coroutineActive && accountCurrent && requests.current("tracks", token))
        if (requests.finish("tracks", token)) loading = false
        assertFalse(loading)
    }
    @Test fun oldFinallyCannotClearReplacementRequestsLoading() {
        val requests = KugouRequestIdentity()
        val old = requests.begin("tracks")
        val replacement = requests.begin("tracks")
        var loading = true
        if (requests.finish("tracks", old)) loading = false
        assertTrue(loading)
        assertTrue(requests.current("tracks", replacement))
        if (requests.finish("tracks", replacement)) loading = false
        assertFalse(loading)
    }
    @Test fun cancelledPoolWaiterDoesNotBlockTheNextHomeRequest() = runBlocking {
        val requests = KugouRequestIdentity()
        val oldPool = CompletableDeferred<List<Int>>()
        val old = requests.begin("tracks")
        var loading = true
        val oldWaiter = launch(start = CoroutineStart.UNDISPATCHED) {
            try { oldPool.await() }
            finally { if (requests.finish("tracks", old)) loading = false }
        }
        assertTrue(requests.cancel("tracks"))
        val replacement = requests.begin("tracks")
        oldPool.cancel()
        oldWaiter.join()
        assertTrue(loading)
        val replacementPool = CompletableDeferred(listOf(1, 2, 3))
        val result = try { replacementPool.await() }
        finally { if (requests.finish("tracks", replacement)) loading = false }
        assertEquals(listOf(1, 2, 3), result)
        assertFalse(loading)
    }
    @Test fun independentRequestsCannotFinishOneAnother() {
        val requests = KugouRequestIdentity()
        val tracks = requests.begin("tracks")
        val library = requests.begin("library")
        assertFalse(requests.finish("library", tracks))
        assertTrue(requests.finish("tracks", tracks))
        assertTrue(requests.current("library", library))
    }
}
