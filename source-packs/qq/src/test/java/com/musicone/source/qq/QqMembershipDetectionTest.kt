package com.musicone.source.qq

import com.musicone.source.qq.internal.QqMembership
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class QqMembershipDetectionTest {
    @Test fun readingDeclarationNeverStartsAQuery() {
        var queries = 0
        val detector = QqMembershipDetection({ "credential" to 1L }, { queries++; null })
        repeat(3) { assertNull(detector.currentMembership) }
        assertEquals(0, queries)
    }

    @Test fun confirmedMemberIsCachedAndConcurrentTrackChecksShareOneQuery() = runBlocking {
        val queries = AtomicInteger()
        val detector = QqMembershipDetection({ "credential" to 1L }, {
            queries.incrementAndGet()
            QqMembership(true, validUntilMs = Long.MAX_VALUE)
        })
        val values = List(8) { async { detector.detectMembership() } }.awaitAll()
        assertTrue(values.all { it == true })
        assertEquals(1, queries.get())
        assertEquals(true, detector.currentMembership)
    }

    @Test fun failedRefreshReplacesPreviousTrueWithUnknown() = runBlocking {
        var now = 1_000L
        var queries = 0
        val detector = QqMembershipDetection({ "credential" to 1L }, {
            if (++queries > 1) throw IOException("网络不可用")
            QqMembership(true, validUntilMs = 2_000L, vipUntilMs = 10_000L)
        }, { now })
        assertEquals(true, detector.detectMembership())
        now = 2_000L
        assertNull(detector.detectMembership())
        assertNull(detector.currentMembership)
        assertNull(detector.detectMembership())
        assertEquals(2, queries)
    }

    @Test fun confirmedNonMemberUsesUnknownAndIsCached() = runBlocking {
        var queries = 0
        val detector = QqMembershipDetection({ "credential" to 1L }, {
            queries++
            QqMembership(false, validUntilMs = Long.MAX_VALUE)
        })
        assertNull(detector.detectMembership())
        assertNull(detector.detectMembership())
        assertEquals(1, queries)
    }

    @Test fun changingAccountDiscardsPreviousMembershipAndLateResult() = runBlocking {
        var current = "first" to 1L
        val detector = QqMembershipDetection({ current }, {
            current = "second" to 2L
            QqMembership(true, validUntilMs = Long.MAX_VALUE)
        })
        assertNull(detector.detectMembership())
        assertNull(detector.currentMembership)
    }

    @Test fun realMembershipExpiryStopsDeclarationWithoutANetworkRead() = runBlocking {
        var now = 1_000L
        val detector = QqMembershipDetection({ "credential" to 1L }, {
            QqMembership(true, validUntilMs = 2_000L, vipUntilMs = 1_500L)
        }, { now })
        assertEquals(true, detector.detectMembership())
        now = 1_500L
        assertNull(detector.currentMembership)
    }
}
