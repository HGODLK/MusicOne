package com.musicone.source.kugou

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class KugouRecentOpenRequestTest {
    @Test fun replacementVerificationKeepsOpeningIntentUntilLatestReadSettles() = runBlocking {
        val expected = KugouHomeOwner("42", 1)
        var value = KugouHomeState(owner = expected, loadingRecent = true)
        val firstReady = CompletableDeferred<Unit>()
        val replacementReady = CompletableDeferred<Unit>()
        var current: Job? = null
        val replacement = launch(start = CoroutineStart.LAZY) {
            replacementReady.await()
            value = value.copy(recentLoaded = true, loadingRecent = false)
        }
        val first = launch {
            firstReady.await()
            value = value.copy(recentLoaded = true, loadingRecent = true)
            current = replacement
            replacement.start()
            currentCoroutineContext().cancel()
        }
        current = first
        var reads = 0
        var opens = 0
        val request = KugouRecentOpenRequest(this, { expected }, { value }, { reads++; requireNotNull(current) })
        request.setAvailable(true)
        val opening = request.open { opens++ }
        yield()
        firstReady.complete(Unit)
        first.join()
        yield()
        assertEquals(0, opens)
        assertEquals(2, reads)
        assertSame(opening, request.open { opens++ })
        replacementReady.complete(Unit)
        opening!!.join()
        assertEquals(1, opens)
    }

    @Test fun firstClickWaitsForOneExistingReadAndRepeatedClicksOpenOnce() = runBlocking {
        val expected = KugouHomeOwner("42", 1)
        var value = KugouHomeState(owner = expected, loadingRecent = true)
        val ready = CompletableDeferred<Unit>()
        val existing = launch { ready.await(); value = value.copy(recentLoaded = true, loadingRecent = false) }
        var reads = 0
        var opens = 0
        val request = KugouRecentOpenRequest(this, { expected }, { value }, { reads++; existing })
        request.setAvailable(true)
        val first = request.open { opens++ }
        assertSame(first, request.open { opens++ })
        yield()
        assertEquals(1, reads)
        assertEquals(0, opens)
        ready.complete(Unit)
        first!!.join()
        assertEquals(1, opens)
    }

    @Test fun leavingCancelsOnlyOpeningIntentAndReturningDoesNotReviveIt() = runBlocking {
        val expected = KugouHomeOwner("42", 1)
        var value = KugouHomeState(owner = expected)
        val ready = CompletableDeferred<Unit>()
        val existing = launch { ready.await(); value = value.copy(recentLoaded = true) }
        var opens = 0
        val request = KugouRecentOpenRequest(this, { expected }, { value }, { existing })
        request.setAvailable(true)
        val pending = request.open { opens++ }
        yield()
        request.setAvailable(false)
        request.setAvailable(true)
        assertFalse(existing.isCancelled)
        ready.complete(Unit)
        existing.join(); pending!!.join()
        assertEquals(0, opens)
        request.open { opens++ }
        assertEquals(1, opens)
    }

    @Test fun changedSessionCannotOpenEvenWhenAccountIdReturnsToSameValue() = runBlocking {
        var expected = KugouHomeOwner("42", 1)
        var value = KugouHomeState(owner = expected)
        val ready = CompletableDeferred<Unit>()
        val existing = launch { ready.await(); value = value.copy(recentLoaded = true) }
        var opens = 0
        val request = KugouRecentOpenRequest(this, { expected }, { value }, { existing })
        request.setAvailable(true)
        val pending = request.open { opens++ }
        yield()
        expected = KugouHomeOwner("42", 2)
        ready.complete(Unit)
        pending!!.join()
        assertEquals(0, opens)
    }

    @Test fun failedReadDoesNotOpenAndNextClickCanRetry() = runBlocking {
        val expected = KugouHomeOwner("42", 1)
        var value = KugouHomeState(owner = expected)
        var reads = 0
        var opens = 0
        val request = KugouRecentOpenRequest(this, { expected }, { value }, {
            launch {
                reads++
                value = if (reads == 1) value.copy(recentError = "真实网络失败")
                    else value.copy(recentLoaded = true, recentError = null)
            }
        })
        request.setAvailable(true)
        request.open { opens++ }!!.join()
        assertEquals(0, opens)
        assertEquals("真实网络失败", value.recentError)
        request.open { opens++ }!!.join()
        assertEquals(2, reads)
        assertEquals(1, opens)
    }

    @Test fun cachedSnapshotOpensImmediatelyWithoutStartingRead() = runBlocking {
        val expected = KugouHomeOwner("42", 1)
        val value = KugouHomeState(owner = expected, recentLoaded = true)
        var opens = 0
        val request = KugouRecentOpenRequest(this, { expected }, { value }, { error("不应重启读取") })
        request.setAvailable(true)
        request.open { assertSame(value, it); opens++ }
        assertEquals(1, opens)
    }
}
