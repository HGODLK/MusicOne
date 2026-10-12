package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaybackRuntimeLeaseTest {
    @Test fun pageDestructionKeepsServiceQueueAndTasksUntilServiceStops() {
        val disposed = mutableListOf<Any>()
        val lease = PlaybackRuntimeLease({ Any() }, { disposed.add(it) })
        val page = Any()
        val service = Any()
        val first = lease.acquire(page)
        assertSame(first, lease.acquire(service))
        lease.release(page)
        assertTrue(disposed.isEmpty())
        val recreatedPage = Any()
        assertSame(first, lease.acquire(recreatedPage))
        lease.release(service)
        assertTrue(disposed.isEmpty())
        lease.release(recreatedPage)
        assertEquals(listOf(first), disposed)
        assertNotSame(first, lease.acquire(Any()))
    }

    @Test fun repeatedAcquireAndReleaseDoNotLeakOrDisposeTwice() {
        var disposed = 0
        val lease = PlaybackRuntimeLease({ Any() }, { disposed++ })
        val owner = Any()
        assertSame(lease.acquire(owner), lease.acquire(owner))
        lease.release(Any())
        assertEquals(0, disposed)
        lease.release(owner)
        lease.release(owner)
        assertEquals(1, disposed)
    }
}
