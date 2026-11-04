package com.musicone.source.runtime

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceOperationLeaseTest {
    @Test fun changedAccountRejectsLateResultsAndNestedHostWrites() = runBlocking {
        var revision = 1
        val lease = SourceOperationLease { }
        lease.accountVersion = { revision }
        var written = false
        val result = runCatching { lease.call {
            revision++
            lease.call { written = true }
        } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(written)
        lease.call(checkAccount = false) { revision++ }
        assertEquals(3, revision)
    }
    @Test fun removalCancelsOnlyTheRegisteredOperationAndRejectsLateAccess() = runBlocking {
        var active = true
        val lease = SourceOperationLease { check(active) }
        val request = async(start = CoroutineStart.UNDISPATCHED) { lease.call { awaitCancellation() } }
        active = false
        lease.close()
        request.join()
        assertTrue(request.isCancelled)
        assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        assertThrows(IllegalStateException::class.java) { lease.read { "不能提交" } }
        Unit
    }

    @Test fun accountChangeBeforeCommitDoesNotPublishPreparedSubscription() = runBlocking {
        var revision = 1
        var committed = false
        val lease = SourceOperationLease { }
        lease.accountVersion = { revision }
        val result = runCatching { lease.call {
            val guard = kotlin.coroutines.coroutineContext[SourceCommitGuard]!!
            revision++
            guard.commit { committed = true }
        } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(committed)
    }

    @Test fun capturedCommitGuardRejectsRemovedInstance() = runBlocking {
        var active = true
        var committed = false
        val lease = SourceOperationLease { check(active) }
        val guard = lease.call { kotlin.coroutines.coroutineContext[SourceCommitGuard]!! }
        lease.invalidate { active = false }
        assertThrows(IllegalStateException::class.java) { guard.commit { committed = true } }
        assertFalse(committed)
    }

    @Test fun removalCannotInvalidateInTheMiddleOfFinalCommit() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val lease = SourceOperationLease { check(active.get()) }
        val guard = lease.call { kotlin.coroutines.coroutineContext[SourceCommitGuard]!! }
        val removing = java.util.concurrent.CountDownLatch(1)
        lateinit var removal: Thread
        guard.commit {
            removal = Thread {
                removing.countDown()
                lease.invalidate { active.set(false) }
            }.also { it.start() }
            assertTrue(removing.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(active.get())
        }
        removal.join(2000)
        assertFalse(removal.isAlive)
        assertFalse(active.get())
    }
}
