package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import com.musicone.source.sdk.SourcePlayerSharedElement
import com.musicone.sourceintegration.SourcePlayerLayoutReadiness
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SourcePlayerReadinessTest {
    @Test fun closingBeforePackageLayoutCancelsPendingPreparation() = runBlocking {
        val readiness = SourcePlayerLayoutReadiness()
        val task = async { readiness.awaitReady(emptySet(), { fail(it) }) }
        yield()
        assertFalse(task.isCompleted)
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
    }

    @Test fun failurePageCanOpenWithoutTheBrokenPackagesSharedTargets() = runBlocking {
        val readiness = SourcePlayerLayoutReadiness()
        readiness.markFailed()
        readiness.markReady()
        readiness.awaitReady(setOf(SourcePlayerSharedElement.ARTWORK), { fail(it) })
    }

    @Test fun openingAgainDoesNotReusePreviousPagesTargets() = runBlocking(BroadcastFrameClock()) {
        val readiness = SourcePlayerLayoutReadiness()
        readiness.publish(SourcePlayerSharedElement.TITLE, Any())
        readiness.markReady()
        readiness.awaitReady(setOf(SourcePlayerSharedElement.TITLE), { fail(it) })
        readiness.reset()
        readiness.markReady()
        var failure: String? = null
        readiness.awaitReady(setOf(SourcePlayerSharedElement.TITLE), { failure = it })
        assertTrue(failure.orEmpty().contains("TITLE"))
    }

    @Test fun declaredSharedTargetStillRequiresAHostSource() = runBlocking(BroadcastFrameClock()) {
        val readiness = SourcePlayerLayoutReadiness()
        readiness.publish(SourcePlayerSharedElement.ARTWORK, Any())
        readiness.markReady()
        var failure: String? = null
        readiness.awaitReady(setOf(SourcePlayerSharedElement.ARTWORK), { failure = it }, sourceReady = { false })
        assertTrue(failure.orEmpty().contains("ARTWORK"))
    }
}
