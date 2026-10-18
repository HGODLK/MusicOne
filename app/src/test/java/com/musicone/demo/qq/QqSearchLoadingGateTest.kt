package com.musicone.demo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class QqSearchLoadingGateTest {
    @Test fun coldLoadingWaitsForTheCorrectEndpointAndStopsBehindDetails() {
        assertFalse(searchLoadingReady(true, false, false, .5f, true, 0f, false, false))
        assertTrue(searchLoadingReady(true, false, false, 1f, false, 0f, false, false))
        assertFalse(searchLoadingReady(true, true, false, 1f, false, .8f, true, false))
        assertFalse(searchLoadingReady(true, true, false, 1f, false, 1f, false, false))
        assertTrue(searchLoadingReady(true, true, false, 1f, false, 1f, false, true))
        assertFalse(searchLoadingReady(true, true, true, 1f, false, 1f, false, true))
        assertFalse(searchLoadingReady(false, false, false, 1f, false, 0f, false, false))
        assertFalse(searchLoadingReady(true, false, false, 1f, false, .3f, true, true))
    }

    @Test fun waitingRequestsResumeOnceAndCanBePausedAgain() = runBlocking {
        val gate = QqSearchLoadingGate()
        var started = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady(); started++ }
        assertEquals(0, started)
        gate.update(true)
        first.join()
        assertEquals(1, started)
        gate.pause()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady(); started++ }
        assertEquals(1, started)
        second.cancelAndJoin()
        gate.update(true)
        assertEquals(1, started)
        gate.awaitReady()
    }
}
