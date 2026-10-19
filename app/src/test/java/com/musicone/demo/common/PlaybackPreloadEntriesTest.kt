package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPreloadEntriesTest {
    private fun entries() = PlaybackPreloadEntries<String, String>(
        ttlMs = { 30_000L },
        refreshLeadMs = 5_000L,
    )

    @Test fun completedDownloadWaitsUntilRefreshAndNewTicketNeedsDownload() {
        val entries = entries()
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 0L))
        entries.resolved("a", "first", 0L)
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 0L))
        entries.downloaded("a")
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 24_999L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 25_000L))
        entries.resolved("a", "second", 25_000L)
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 25_000L))
        assertEquals("second", entries.peek("a", 25_000L))
    }

    @Test fun refreshHappensWithoutExtraPermissionAndExpiryAlwaysResolves() {
        val entries = entries()
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 0L, allowRefresh = false))
        entries.resolved("a", "url", 0L)
        entries.downloaded("a")
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 25_000L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 60_000L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 60_000L, allowRefresh = true))
    }

    @Test fun unfinishedDownloadRefreshesBeforeExpiryAndResolvesAfterIt() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "url", 0L)
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 25_000L, allowRefresh = false))
        entries.resolved("a", "renewed", 25_000L)
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 25_000L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 29_999L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 49_999L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 50_000L, allowRefresh = false))
        entries.resolved("a", "renewed-again", 50_000L)
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 50_000L, allowRefresh = false))
    }

    @Test fun completedDownloadRetriesFailedRefreshAfterBackoffWithoutRefreshPermission() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "url", 0L)
        entries.downloaded("a")
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 25_000L))
        entries.failed("a", 25_000L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 34_999L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 35_000L, allowRefresh = false))
        entries.failed("a", 35_000L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 64_999L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 65_000L, allowRefresh = false))
        entries.failed("a", 65_000L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 100_000L, allowRefresh = false))
    }

    @Test fun retryBackoffUsesTenThenThirtySecondsAndStopsAtThirdFailure() {
        val entries = entries()
        entries.work("a", 0L)
        entries.failed("a", 100L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 10_099L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 10_100L))
        entries.failed("a", 10_100L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 40_099L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 40_100L))
        entries.failed("a", 40_100L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", Long.MAX_VALUE))
    }

    @Test fun resolvingNewTicketsDoesNotResetDownloadFailureBudget() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "first", 0L)
        entries.failed("a", 0L, retryable = true)
        entries.resolved("a", "second", 5_000L)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 9_999L))
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 10_000L))
        entries.failed("a", 10_000L, retryable = true)
        entries.resolved("a", "third", 40_000L)
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 40_000L))
        entries.failed("a", 40_000L, retryable = true)
        entries.resolved("a", "fourth", 70_000L)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 70_000L))
        assertEquals("fourth", entries.take("a", 70_000L))
    }

    @Test fun successfulDownloadResetsBudgetAndRetryDeadlineForNextTicket() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "first", 0L)
        entries.failed("a", 0L, retryable = true)
        entries.failed("a", 10_000L, retryable = true)
        entries.downloaded("a")
        entries.resolved("a", "second", 20_000L)
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 20_000L))
        entries.failed("a", 20_000L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 29_999L))
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 30_000L))
        entries.failed("a", 30_000L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 60_000L))
    }

    @Test fun terminalFailureStopsBackgroundWorkButKeepsReadyValueDeliverable() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "url", 0L)
        entries.failed("a", 0L, retryable = false)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 10_000L))
        assertEquals("url", entries.peek("a", 10_000L))
        assertEquals("url", entries.take("a", 10_000L))
        assertNull(entries.peek("a", 10_000L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 10_000L))
    }

    @Test fun retryDelayDoesNotPreventReadyValueDelivery() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "url", 0L)
        entries.failed("a", 0L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 1L))
        assertEquals("url", entries.peek("a", 1L))
        assertEquals("url", entries.peek("a", 1L))
        assertEquals("url", entries.take("a", 1L))
        assertNull(entries.take("a", 1L))
    }

    @Test fun peekAndTakeHonorFreshnessBoundaryAndExpiredTakeClearsFailures() {
        val entries = entries()
        entries.work("a", 1_000L)
        entries.resolved("a", "url", 1_000L)
        assertNull(entries.peek("a", 999L))
        assertEquals("url", entries.peek("a", 25_999L))
        assertNull(entries.peek("a", 26_000L))
        entries.failed("a", 26_000L, retryable = false)
        assertNull(entries.take("a", 26_000L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 26_000L))
    }

    @Test fun lateCallbacksCannotReviveKeysRemovedFromWindow() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "old", 0L)
        entries.retain(emptySet())
        entries.resolved("a", "late", 1L)
        entries.failed("a", 1L, retryable = false)
        entries.downloaded("a")
        assertNull(entries.peek("a", 1L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 1L))
        entries.resolved("a", "new", 1L)
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 1L))
    }

    @Test fun callbacksBeforeFirstWorkDoNotCreateEntries() {
        val entries = entries()
        entries.resolved("a", "late", 0L)
        entries.failed("a", 0L, retryable = false)
        entries.downloaded("a")
        assertNull(entries.take("a", 0L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 0L))
    }

    @Test fun cancelledRefreshRemainsPendingUntilResolved() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "url", 0L)
        entries.downloaded("a")
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 25_000L))
        assertEquals(true, entries.needsRefresh("a", 25_001L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 25_001L, allowRefresh = false))
        entries.resolved("a", "new", 25_001L)
        assertEquals(false, entries.needsRefresh("a", 25_001L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 25_001L, allowRefresh = false))
    }

    @Test fun needsRefreshIsNonConsumingAndHonorsReadinessPermissionAndBudget() {
        val entries = entries()
        assertEquals(false, entries.needsRefresh("missing", 25_000L, allowRefresh = true))
        entries.resolved("missing", "late", 25_000L)
        assertNull(entries.peek("missing", 25_000L))
        entries.work("a", 0L)
        assertEquals(false, entries.needsRefresh("a", 25_000L, allowRefresh = true))
        entries.resolved("a", "url", 0L)
        assertEquals(false, entries.needsRefresh("a", 24_999L, allowRefresh = true))
        assertEquals(true, entries.needsRefresh("a", 25_000L, allowRefresh = false))
        assertEquals(true, entries.needsRefresh("a", 25_000L, allowRefresh = true))
        entries.failed("a", 25_000L, retryable = true)
        assertEquals(false, entries.needsRefresh("a", 34_999L, allowRefresh = true))
        assertEquals(true, entries.needsRefresh("a", 35_000L, allowRefresh = true))
        entries.failed("a", 35_000L, retryable = true)
        entries.failed("a", 65_000L, retryable = true)
        assertEquals(false, entries.needsRefresh("a", 100_000L, allowRefresh = true))
        entries.work("terminal", 0L)
        entries.resolved("terminal", "url", 0L)
        entries.failed("terminal", 0L, retryable = false)
        assertEquals(false, entries.needsRefresh("terminal", 25_000L, allowRefresh = true))
    }

    @Test fun blockedWaitsTenSecondsWithoutConsumingFailureBudget() {
        val entries = entries()
        entries.work("a", 0L)
        entries.resolved("a", "url", 0L)
        entries.downloaded("a")
        entries.blocked("a", 0L)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 9_999L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 10_000L, allowRefresh = false))
        entries.blocked("a", 10_000L)
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 20_000L, allowRefresh = false))
        entries.blocked("a", 20_000L)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 29_999L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 30_000L, allowRefresh = false))
        entries.resolved("a", "new", 30_000L)
        entries.failed("a", 30_000L, retryable = true)
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("a", 39_999L, allowRefresh = false))
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("a", 40_000L, allowRefresh = false))
    }

    @Test fun blockedPreservesReadyValueAndCannotReviveRemovedEntry() {
        val entries = entries()
        entries.blocked("missing", 0L)
        entries.resolved("missing", "late", 0L)
        assertNull(entries.peek("missing", 0L))
        entries.work("a", 0L)
        entries.resolved("a", "url", 0L)
        entries.blocked("a", 0L)
        assertEquals("url", entries.take("a", 1L))
        entries.blocked("a", 1L)
        entries.resolved("a", "late", 1L)
        assertNull(entries.peek("a", 1L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("a", 1L))
    }

    @Test fun retainPreservesKeptStateAndKeysHaveIndependentBudgets() {
        val entries = entries()
        for (key in listOf("failed", "done", "removed")) {
            entries.work(key, 0L)
            entries.resolved(key, key, 0L)
        }
        entries.failed("failed", 0L, retryable = true)
        entries.downloaded("done")
        entries.retain(setOf("failed", "done"))
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("failed", 1L))
        assertEquals(PlaybackPreloadEntries.Work.NONE, entries.work("done", 1L))
        assertEquals("done", entries.peek("done", 1L))
        assertNull(entries.peek("removed", 1L))
        assertEquals(PlaybackPreloadEntries.Work.DOWNLOAD, entries.work("failed", 10_000L))
        assertEquals(PlaybackPreloadEntries.Work.RESOLVE, entries.work("removed", 1L))
        assertEquals("failed", entries.take("failed", 10_000L))
        assertEquals("done", entries.take("done", 10_000L))
    }
}
