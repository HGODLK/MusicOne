package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import org.junit.Assert.*
import org.junit.Test

class QqRecentPlayReporterTest {
    @Test fun cancelledReportRemainsCancelledInsteadOfCompletingAsAnOrdinaryFailure() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var reportJob: Job? = null
        try {
            QqRecentPlayReporter(scope) {
                reportJob = currentCoroutineContext()[Job]
                throw CancellationException("停止上报")
            }.report(track(), 1L)
            assertNotNull(reportJob)
            assertTrue(reportJob!!.isCancelled)
        } finally { scope.cancel() }
    }

    @Test fun ordinaryFailuresStayIsolatedAndGenerationDeduplicationIsPreserved() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val jobs = mutableListOf<Job>()
        try {
            val reporter = QqRecentPlayReporter(scope) {
                jobs += currentCoroutineContext()[Job]!!
                error("网络失败")
            }
            reporter.report(track(), 1L)
            reporter.report(track(), 1L)
            reporter.report(track(), 2L)
            assertEquals(2, jobs.size)
            assertTrue(jobs.all { it.isCompleted && !it.isCancelled })
            assertTrue(scope.coroutineContext[Job]!!.isActive)
        } finally { scope.cancel() }
    }

    private fun track() = MusicTrack("003abc123abc12", MusicSource.QQ, "歌曲", "歌手", "", 240_000,
        0, 0, "", "").copy(catalogId = "123456")
}
