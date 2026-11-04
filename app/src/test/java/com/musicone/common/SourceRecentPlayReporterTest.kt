package com.musicone

import com.musicone.sourceintegration.SourceRecentPlayReporter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.*
import org.junit.Test

class SourceRecentPlayReporterTest {
    @Test fun actualListeningTimeIsReportedOnceAndPublishesSuccessfulChange() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var playedMs = 0L
        var notifications = 0
        try {
            val reporter = SourceRecentPlayReporter(scope, { _, elapsed -> playedMs = elapsed; true }, { notifications++ })
            reporter.report(track(), 1L, 0L)
            assertEquals(0, notifications)
            reporter.report(track(), 1L, 1_234L)
            reporter.report(track(), 1L, 9_000L)
            assertEquals(1_234L, playedMs)
            assertEquals(1, notifications)
        } finally { scope.cancel() }
    }
    @Test fun cancelledReportRemainsCancelledInsteadOfCompletingAsAnOrdinaryFailure() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var reportJob: Job? = null
        try {
            SourceRecentPlayReporter(scope, { _, _ ->
                reportJob = currentCoroutineContext()[Job]
                throw CancellationException("停止上报")
            }).report(track(), 1L, 1_000L)
            assertNotNull(reportJob)
            assertTrue(reportJob!!.isCancelled)
        } finally { scope.cancel() }
    }

    @Test fun ordinaryFailuresStayIsolatedAndGenerationDeduplicationIsPreserved() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val jobs = mutableListOf<Job>()
        try {
            val reporter = SourceRecentPlayReporter(scope, { _, _ ->
                jobs += currentCoroutineContext()[Job]!!
                error("网络失败")
            })
            reporter.report(track(), 1L, 1_000L)
            reporter.report(track(), 1L, 1_000L)
            reporter.report(track(), 2L, 1_000L)
            assertEquals(2, jobs.size)
            assertTrue(jobs.all { it.isCompleted && !it.isCancelled })
            assertTrue(scope.coroutineContext[Job]!!.isActive)
        } finally { scope.cancel() }
    }

    private fun track() = MusicTrack("003abc123abc12", MusicSource.QQ, "歌曲", "歌手", "", 240_000,
        0, 0, "", "").copy(catalogId = "123456")
}
