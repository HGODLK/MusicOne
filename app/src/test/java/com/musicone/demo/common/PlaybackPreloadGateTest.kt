package com.musicone.demo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackPreloadGateTest {
    @Test fun sourceRequestsAreSerializedWithOneJitterBeforeEachRequest() = runBlocking {
        val waits = mutableListOf<Long>()
        val gate = PlaybackPreloadGate(delayMs = { 300L }, wait = { waits += it; yield() })
        var active = 0
        var highest = 0
        coroutineScope {
            repeat(7) {
                launch {
                    gate.request {
                        highest = maxOf(highest, ++active)
                        yield()
                        active--
                    }
                }
            }
        }
        assertEquals(1, highest)
        assertEquals(List(7) { 300L }, waits)
    }
    @Test fun downloadsCanOverlapAfterSerializedSourceResolution() = runBlocking {
        val gate = PlaybackPreloadGate(delayMs = { 5000L }, wait = { yield() })
        val bothStarted = CompletableDeferred<Unit>()
        var downloads = 0
        withTimeout(2_000L) {
            coroutineScope {
                repeat(2) {
                    launch {
                        gate.request { yield() }
                        if (++downloads == 2) bothStarted.complete(Unit)
                        bothStarted.await()
                    }
                }
            }
        }
        assertEquals(2, downloads)
    }
    @Test fun bufferingDefersNewRequestsUntilCurrentPlaybackRecovers() = runBlocking {
        var allowed = false
        var checks = 0
        val gate = PlaybackPreloadGate(delayMs = { 300L }, wait = {
            if (it == 250L && ++checks == 2) allowed = true
        }, mayStart = { allowed })
        gate.request { assertTrue(allowed) }
        assertEquals(2, checks)
    }
    @Test fun canceledWaitingRequestNeverFetchesAndDoesNotBlockNextRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var first = true
        val gate = PlaybackPreloadGate(delayMs = { 300L }, wait = {
            if (first) { first = false; entered.complete(Unit); awaitCancellation() }
        })
        var called = false
        val job = launch { gate.request { called = true } }
        entered.await()
        job.cancelAndJoin()
        assertFalse(called)
        withTimeout(2_000L) { gate.request { called = true } }
        assertTrue(called)
    }
}
