package com.musicone.demo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class UsbOutputWorkerTest {
    private class Transport : UsbOutputTransport {
        @Volatile override var completedFrames = 0L
        @Volatile var pending = false
        @Volatile var failFinish = false
        @Volatile var failFlush = false
        val repeatedFlush = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val draining = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val flushes = AtomicInteger()
        override fun interrupt() { interrupted.countDown(); completed.countDown() }
        override fun flush() {
            if (flushes.incrementAndGet() > 1) repeatedFlush.countDown()
            check(!failFlush) { "模拟清理失败" }
            pending = false
            completedFrames = 0
        }
        override fun pollPending() = pending
        override fun finish() {
            draining.countDown()
            check(completed.await(2, TimeUnit.SECONDS))
            check(!failFinish) { "模拟 USB 传输错误" }
            pending = false
        }
    }

    @Test fun lastDequeuedBufferAndNativeTailRemainPendingUntilCompletion() {
        val transport = Transport()
        val writing = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val worker = UsbOutputWorker<Int>(transport, {
            writing.countDown()
            check(releaseWrite.await(2, TimeUnit.SECONDS))
            transport.pending = true
        }, {})
        worker.start()
        try {
            assertTrue(worker.enqueue(1))
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            worker.finish()
            assertEquals(0, worker.queueSize())
            assertTrue(worker.hasPendingData())
            assertFalse(worker.isEnded())
            releaseWrite.countDown()
            assertTrue(transport.draining.await(2, TimeUnit.SECONDS))
            assertTrue(worker.hasPendingData())
            assertFalse(worker.isEnded())
            transport.completed.countDown()
            await { worker.isEnded() }
            assertFalse(worker.hasPendingData())
        } finally { releaseWrite.countDown(); worker.stop() }
    }

    @Test fun seekWaitsForOldWriterAndResetsItsLateProgressBeforeAcceptingNewData() {
        val transport = Transport()
        val writing = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val recycled = AtomicInteger()
        val worker = UsbOutputWorker<Int>(transport, { value ->
            if (value == 1) {
                writing.countDown()
                check(releaseWrite.await(2, TimeUnit.SECONDS))
                transport.completedFrames = 999
            } else transport.completedFrames = value.toLong()
        }, { recycled.incrementAndGet() })
        val executor = Executors.newSingleThreadExecutor()
        worker.start()
        try {
            worker.enqueue(1)
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            worker.enqueue(2)
            val flush = executor.submit { worker.flush() }
            assertTrue(transport.interrupted.await(2, TimeUnit.SECONDS))
            assertFalse(flush.isDone)
            assertFalse(worker.enqueue(3))
            releaseWrite.countDown()
            flush.get(2, TimeUnit.SECONDS)
            assertEquals(0L, worker.completedFrames)
            assertEquals(2, recycled.get())
            assertFalse(worker.hasPendingData())
            assertTrue(worker.enqueue(4))
            await { worker.completedFrames == 4L }
        } finally { releaseWrite.countDown(); worker.stop(); executor.shutdownNow() }
    }

    @Test fun pausedAndRepeatedSeekDoesNotWriteOrLeaveEndStateBehind() {
        val transport = Transport()
        val writes = AtomicInteger()
        val worker = UsbOutputWorker<Int>(transport, { writes.incrementAndGet() }, {})
        worker.pause()
        worker.start()
        try {
            repeat(3) { worker.enqueue(it); worker.finish(); worker.flush() }
            assertEquals(3, transport.flushes.get())
            assertEquals(0, writes.get())
            assertFalse(worker.isEnded())
            assertTrue(worker.enqueue(10))
            worker.resume()
            await { writes.get() == 1 }
        } finally { worker.stop() }
    }

    @Test fun failedDrainIsNotReportedAsNormalEnd() {
        val transport = Transport().apply { failFinish = true; completed.countDown() }
        val worker = UsbOutputWorker<Int>(transport, {}, {})
        worker.start()
        try {
            worker.finish()
            await { worker.error() != null }
            assertFalse(worker.isEnded())
        } finally { worker.stop() }
    }

    @Test fun seekInterruptsAnAlreadyRunningEndDrain() {
        val transport = Transport().apply { pending = true }
        val worker = UsbOutputWorker<Int>(transport, {}, {})
        worker.start()
        try {
            worker.enqueue(1)
            worker.finish()
            assertTrue(transport.draining.await(2, TimeUnit.SECONDS))
            worker.flush()
            assertFalse(worker.isEnded())
            assertFalse(worker.hasPendingData())
            assertTrue(worker.enqueue(2))
        } finally { worker.stop() }
    }

    @Test fun failedFlushWaitsForExplicitRetryWithoutAcceptingNewData() {
        val transport = Transport().apply { failFlush = true }
        val worker = UsbOutputWorker<Int>(transport, {}, {})
        worker.start()
        try {
            try { worker.flush(); fail("清理失败必须上报") }
            catch (_: IllegalStateException) { }
            assertNotNull(worker.error())
            assertFalse(worker.enqueue(1))
            worker.resume()
            assertFalse(transport.repeatedFlush.await(50, TimeUnit.MILLISECONDS))
            transport.failFlush = false
            worker.flush()
            assertEquals(2, transport.flushes.get())
            assertNull(worker.error())
            assertTrue(worker.enqueue(2))
        } finally { worker.stop() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue(condition())
    }
}
