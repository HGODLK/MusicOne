package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RunCatchingCancellableTest {
    @Test fun successfulValuesAndOrdinaryFailuresKeepTheirExistingFallbacks() {
        assertEquals("结果", runCatchingCancellable { "结果" }.getOrThrow())
        val failure = IllegalStateException("请求失败")
        var observed: Throwable? = null
        assertEquals("备用结果", runCatchingCancellable<String> { throw failure }
            .onFailure { observed = it }.getOrDefault("备用结果"))
        assertSame(failure, observed)
    }

    @Test fun cancellationEscapesBeforeFailureHandlersOrFallbackRequests() {
        val cancellation = CancellationException("账号已切换")
        var failureHandled = false
        var fallbackRequested = false
        try {
            runCatchingCancellable<String> { throw cancellation }
                .onFailure { failureHandled = true }
                .getOrElse { fallbackRequested = true; "旧账号结果" }
            fail("取消不能转换为成功结果")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        assertFalse(failureHandled)
        assertFalse(fallbackRequested)
    }

    @Test fun cancellingSuspendedWorkRunsCleanupWithoutPublishingFailureOrOldResults() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var cleanedUp = false
        var failurePublished = false
        var resultPublished = false
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable {
                try {
                    entered.complete(Unit)
                    awaitCancellation()
                } finally { cleanedUp = true }
            }.onFailure { failurePublished = true }
            resultPublished = true
        }
        entered.await()
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertTrue(cleanedUp)
        assertFalse(failurePublished)
        assertFalse(resultPublished)
    }
}
