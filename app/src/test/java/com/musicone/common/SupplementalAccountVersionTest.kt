package com.musicone

import com.musicone.source.sdk.SourceAccount
import com.musicone.source.sdk.SourceAccountState
import com.musicone.source.sdk.SourceSessionStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SupplementalAccountVersionTest {
    private fun account(id: String = "first", revision: Long = 1) =
        SourceAccountState(SourceSessionStatus.AUTHENTICATED, SourceAccount(id, id), revision)

    @Test fun sameAccountRevisionChangeInvalidatesPreviouslyCapturedSession() {
        val version = SupplementalAccountVersion()
        val original = account()
        assertTrue(version.update(original))
        assertFalse(version.update(original))
        assertFalse(version.matches(original.copy(revision = 2)))
        assertTrue(version.update(original.copy(revision = 2)))
        assertTrue(version.matches(original.copy(revision = 2)))
    }

    @Test fun accountAndStatusChangesDoNotDependOnRevisionIncreasing() {
        val version = SupplementalAccountVersion()
        version.update(account())
        assertTrue(version.update(account("second")))
        assertTrue(version.update(account("second").copy(status = SourceSessionStatus.EXPIRED)))
    }

    @Test fun summaryUpdatesKeepTheCurrentSessionAndNavigation() {
        val version = SupplementalAccountVersion()
        val original = account()
        version.update(original)
        assertFalse(version.update(original.copy(account = original.account!!.copy(displayName = "新昵称"))))
    }

    @Test fun anonymousSessionRequiresInitialObservationAndTracksLoginLogout() {
        val version = SupplementalAccountVersion()
        assertFalse(version.matches(null))
        assertTrue(version.update(null))
        assertTrue(version.matches(null))
        assertTrue(version.update(account()))
        assertTrue(version.update(null))
    }

    @Test fun cancelOldAccountWorkKeepsTheScopeAvailableForTheNextSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val waiting = CompletableDeferred<Unit>()
            val oldImport = scope.launch { waiting.await() }
            val oldSubscription = scope.launch { waiting.await() }
            cancelSupplementalAccountRequests(scope)
            assertTrue(oldImport.isCancelled)
            assertTrue(oldSubscription.isCancelled)
            assertTrue(scope.isActive)
            val current = scope.async { "新账号请求" }
            assertEquals("新账号请求", current.await())
        } finally { scope.cancel() }
    }
}
