package com.musicone.source.kugou

import java.io.InterruptedIOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class KugouContentCancellationTest {
    @Test fun interruptedIoFromCancelledReadRetainsCancellationSemantics() = runBlocking {
        val failure = CompletableDeferred<Throwable>()
        val client = KugouContentClient(KugouContentTransport { _, _ ->
            currentCoroutineContext().cancel()
            throw InterruptedIOException("thread interrupted")
        })
        val job = launch {
            try { client.load(KugouContentRequest("/test"), "") }
            catch (error: Exception) { failure.complete(error) }
        }
        job.join()
        assertTrue(failure.await() is CancellationException)
        assertTrue(job.isCancelled)
    }

    @Test fun interruptedIoWhileActiveRemainsARealFailure() = runBlocking {
        val expected = InterruptedIOException("真实读取失败")
        val client = KugouContentClient(KugouContentTransport { _, _ -> throw expected })
        val actual = try { client.load(KugouContentRequest("/test"), ""); null }
            catch (error: Exception) { error }
        assertSame(expected, actual)
        assertTrue(currentCoroutineContext().isActive)
    }
}
