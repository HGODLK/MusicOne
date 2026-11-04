package com.musicone.sourceintegration

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class SourceStartupTest {
    @Test fun pageCancellationDoesNotCancelStartupOrRunItTwice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val startup = SourceStartup(scope) { starts++; release.await() }
        try {
            startup.start()
            assertTrue(startup.state.value.initializing)
            val firstPage = launch(start = CoroutineStart.UNDISPATCHED) { startup.await() }
            firstPage.cancelAndJoin()
            assertTrue(startup.state.value.initializing)
            val newPage = async(start = CoroutineStart.UNDISPATCHED) { startup.await() }
            assertFalse(newPage.isCompleted)
            release.complete(Unit)
            newPage.await()
            assertFalse(startup.state.value.initializing)
            assertNull(startup.state.value.error)
            assertEquals(1, starts)
        } finally { scope.cancel() }
    }

    @Test fun failureReleasesWaitersAndPreservesDiagnostic() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        val startup = SourceStartup(scope) { release.await(); error("签名清单不一致") }
        try {
            val waiting = async(start = CoroutineStart.UNDISPATCHED) { startup.await() }
            release.complete(Unit)
            waiting.await()
            assertFalse(startup.state.value.initializing)
            assertEquals("签名清单不一致", startup.state.value.error)
        } finally { scope.cancel() }
    }
}
