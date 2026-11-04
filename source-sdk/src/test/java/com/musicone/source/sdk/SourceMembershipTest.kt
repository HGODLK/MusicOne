package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Test

class SourceMembershipTest {
    private open class Entry : SourceEntry {
        override val descriptor = SourceDescriptor("org.example.membership", "会员测试", SourceKind.MAIN, 1, "1", emptySet())
        override val implementedCapabilities = emptySet<SourceCapability>()
        override fun initialize(environment: SourceEnvironment) {}
        override fun close() {}
        @Composable override fun Content(route: SourceRoute) {}
    }
    private fun entry(detector: SourceMembershipDetector?) = object : Entry(), SourceMembershipProvider {
        override val membershipDetector = detector
    }

    @Test fun legacyAndDefaultProviderDoNotDeclareMembership() {
        listOf(Entry(), object : Entry(), SourceMembershipProvider {}).forEach { value ->
            assertNull(value.membershipDetectorOrNull())
            assertEquals(false, value.currentMembership())
            assertEquals(false, immediate { value.detectMembership() })
        }
        assertEquals(1, SourceSdk.API_VERSION)
        assertFalse(SourceEntry::class.java.declaredMethods.any { it.name.contains("Membership") })
    }

    @Test fun declarationAndCurrentReadDoNotStartDetection() {
        var calls = 0
        val value = entry(object : SourceMembershipDetector {
            override suspend fun detectMembership(): Boolean? { calls++; return true }
        })
        assertNotNull(value.membershipDetectorOrNull())
        assertNull(value.currentMembership())
        assertEquals(0, calls)
        assertEquals(true, immediate { value.detectMembership() })
        assertEquals(1, calls)
    }

    @Test fun declaredNonMemberAndUnknownStayUnknown() {
        listOf<Boolean?>(false, null, true).forEach { result ->
            val value = entry(object : SourceMembershipDetector {
                override val currentMembership = result
                override suspend fun detectMembership() = result
            })
            val expected = result.takeIf { it == true }
            assertEquals(expected, value.currentMembership())
            assertEquals(expected, immediate { value.detectMembership() })
        }
    }

    @Test fun ordinaryDetectionFailureDoesNotBecomeUndeclaredFalse() {
        val value = entry(object : SourceMembershipDetector {
            override suspend fun detectMembership(): Boolean? = throw java.io.IOException("网络失败")
        })
        assertNull(immediate { value.detectMembership() })
    }

    @Test fun cancellationIsPropagated() {
        val cancellation = CancellationException("账号变化")
        val value = entry(object : SourceMembershipDetector {
            override suspend fun detectMembership(): Boolean? = throw cancellation
        })
        assertSame(cancellation, runCatching { immediate { value.detectMembership() } }.exceptionOrNull())
    }

    private fun <T> immediate(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return checkNotNull(result) { "测试操作应同步完成" }.getOrThrow()
    }
}
