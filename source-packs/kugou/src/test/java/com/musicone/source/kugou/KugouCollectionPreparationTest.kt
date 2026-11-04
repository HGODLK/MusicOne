package com.musicone.source.kugou

import com.musicone.source.sdk.SourceOperationException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class KugouCollectionPreparationTest {
    @Test fun failedCoverCanRetryOnNextReadWithoutChangingDirectoryVersion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preparation = KugouCollectionArtworkPreparation()
        val calls = AtomicInteger()
        try {
            preparation.prepare(scope, "42:1:20", "v1") {
                calls.incrementAndGet(); throw java.io.IOException("暂时断网")
            }.join()
            preparation.prepare(scope, "42:1:20", "v1") { calls.incrementAndGet() }.join()
            assertEquals(2, calls.get())
        } finally { preparation.clear(); scope.cancel() }
    }

    @Test fun pendingCoverDoesNotBlockDirectoryAndSameVersionIsCoalesced() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preparation = KugouCollectionArtworkPreparation()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        try {
            preparation.prepare(scope, "42:1:20", "v1") {
                calls.incrementAndGet(); entered.complete(Unit); release.await(); finished.complete(Unit)
            }
            withTimeout(5_000) { entered.await() }
            preparation.prepare(scope, "42:1:20", "v1") { calls.incrementAndGet() }
            assertEquals(1, calls.get())
            release.complete(Unit)
            withTimeout(5_000) { finished.await() }
            preparation.prepare(scope, "42:1:20", "v1") { calls.incrementAndGet() }
            assertEquals(1, calls.get())
        } finally { preparation.clear(); scope.cancel() }
    }

    @Test fun invalidationCancelsOldCoverAndAllowsSameVersionToReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preparation = KugouCollectionArtworkPreparation()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<Unit>()
        try {
            preparation.prepare(scope, "42:1:20", "v1") {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            withTimeout(5_000) { entered.await() }
            preparation.clear()
            preparation.prepare(scope, "42:1:20", "v1") { fresh.complete(Unit) }
            withTimeout(5_000) { cancelled.await(); fresh.await() }
        } finally { preparation.clear(); scope.cancel() }
    }

    @Test fun acceptedCreationReadbackFailureUsesExistingUnconfirmedCode() = runBlocking {
        val failure = try {
            confirmKugouCreatedCollection<Unit> { throw java.io.IOException("回读失败") }
            error("应返回待确认")
        } catch (failure: SourceOperationException) { failure }
        assertEquals("COLLECTION_CREATE_UNCONFIRMED", failure.code)
        assertFalse(failure.retryable)
        val cancellation = CancellationException("换号")
        try {
            confirmKugouCreatedCollection<Unit> { throw cancellation }
            fail("必须保留取消")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertEquals("20", confirmKugouCreatedCollection { "20" })
    }
}
