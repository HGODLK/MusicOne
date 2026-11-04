package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceMembershipLeaseTest {
    private open class Entry(kind: SourceKind = SourceKind.MAIN) : SourceEntry {
        override val descriptor = SourceDescriptor("org.example.membership", "会员测试", kind, 1, "1", emptySet())
        override val implementedCapabilities = emptySet<SourceCapability>()
        override fun initialize(environment: SourceEnvironment) {}
        override fun close() {}
        @Composable override fun Content(route: SourceRoute) {}
    }
    private fun entry(detector: SourceMembershipDetector, kind: SourceKind = SourceKind.MAIN) =
        object : Entry(kind), SourceMembershipProvider { override val membershipDetector = detector }

    @Test fun absentAndDefaultProvidersRemainDisabledThroughGuard() = runBlocking {
        val lease = SourceOperationLease {}
        try {
            listOf(Entry(), object : Entry(), SourceMembershipProvider {}).forEach { original ->
                val guarded = SourceGuardedEntry(original, lease)
                assertNull(guarded.membershipDetectorOrNull())
                assertEquals(false, guarded.currentMembership())
                assertEquals(false, guarded.detectMembership())
            }
        } finally { lease.close() }
    }

    @Test fun mainAndSupplementalUseTheSameOptionalDetectionWithoutAutomaticRequests() = runBlocking {
        var calls = 0
        val detector = object : SourceMembershipDetector {
            override val currentMembership = true
            override suspend fun detectMembership(): Boolean? { calls++; return true }
        }
        SourceKind.entries.forEach { kind ->
            val lease = SourceOperationLease {}
            try {
                val guarded = SourceGuardedEntry(entry(detector, kind), lease)
                assertEquals(true, guarded.currentMembership())
                val before = calls
                assertNotNull(guarded.membershipDetectorOrNull())
                assertEquals(before, calls)
                assertEquals(true, guarded.detectMembership())
                assertEquals(before + 1, calls)
            } finally { lease.close() }
        }
    }

    @Test fun accountChangeRejectsLateMembershipResult() = runBlocking {
        var account = "42"
        val lease = SourceOperationLease {}.apply { accountVersion = { account } }
        val detector = object : SourceMembershipDetector {
            override suspend fun detectMembership(): Boolean? { account = "77"; return true }
        }
        try {
            val guarded = SourceGuardedEntry(entry(detector), lease)
            assertTrue(runCatching { guarded.detectMembership() }.exceptionOrNull() is CancellationException)
        } finally { lease.close() }
    }

    @Test fun capturedDetectorCannotBeReusedAfterAccountChange() = runBlocking {
        var account = "42"
        var calls = 0
        val lease = SourceOperationLease {}.apply { accountVersion = { account } }
        val detector = object : SourceMembershipDetector {
            override suspend fun detectMembership(): Boolean? { calls++; return true }
        }
        try {
            val captured = SourceGuardedEntry(entry(detector), lease).membershipDetectorOrNull()!!
            account = "77"
            assertTrue(runCatching { captured.detectMembership() }.exceptionOrNull() is CancellationException)
            assertTrue(runCatching { captured.currentMembership }.exceptionOrNull() is CancellationException)
            assertEquals(0, calls)
        } finally { lease.close() }
    }

    @Test fun removalBlocksCapturedReadsAndCancelsActiveDetection() = runBlocking {
        var active = true
        val lease = SourceOperationLease { check(active) }
        val detector = object : SourceMembershipDetector {
            override val currentMembership = true
            override suspend fun detectMembership(): Boolean? = awaitCancellation()
        }
        val captured = SourceGuardedEntry(entry(detector), lease).membershipDetectorOrNull()!!
        val pending = async(start = CoroutineStart.UNDISPATCHED) { captured.detectMembership() }
        active = false
        lease.close()
        pending.join()
        assertTrue(pending.isCancelled)
        assertThrows(IllegalStateException::class.java) { captured.currentMembership }
        assertTrue(coroutineContext[kotlinx.coroutines.Job]!!.isActive)
        Unit
    }
}
