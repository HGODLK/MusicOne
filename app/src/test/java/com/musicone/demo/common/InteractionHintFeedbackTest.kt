package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class InteractionHintFeedbackTest {
    private class Session : InteractionHintSession {
        val targets = mutableListOf<Long>()
        val reports = mutableListOf<Long>()
        var closes = 0
        override fun target(nanos: Long) { targets += nanos }
        override fun report(nanos: Long) { reports += nanos }
        override fun close() { closes++ }
    }

    @Test fun onlyMeasuredUiStagesAreReportedAndOtherFrameTimeKeepsItsBudget() {
        val session = Session()
        val starts = mutableListOf<Long>()
        val feedback = InteractionHintFeedback { starts += it; session }
        feedback.setActive(true)
        assertTrue(starts.isEmpty())
        feedback.report(8000L, 4000L, longArrayOf(100L, 200L, 300L, 400L))
        feedback.report(8000L, 4000L, longArrayOf(100L, 200L, 0L, 0L))
        assertEquals(listOf(5000L), starts)
        assertEquals(listOf(4300L), session.targets)
        assertEquals(listOf(1000L, 300L), session.reports)
        feedback.close()
    }

    @Test fun unsupportedDeviceIsNotRetriedOnEveryInteraction() {
        var attempts = 0
        var disabled = 0
        val feedback = InteractionHintFeedback(onDisabled = { disabled++ }) { attempts++; null }
        repeat(5) { feedback.setActive(true); feedback.report(10L, 5L, longArrayOf(1L)); feedback.setActive(false) }
        assertEquals(1, attempts)
        assertEquals(1, disabled)
        feedback.close()
    }

    @Test fun repeatedStartSharesSessionAndLateFramesCannotReviveClosedSession() {
        val sessions = mutableListOf<Session>()
        val feedback = InteractionHintFeedback { Session().also(sessions::add) }
        feedback.setActive(true); feedback.setActive(true)
        feedback.report(10L, 5L, longArrayOf(1L))
        assertEquals(1, sessions.size)
        feedback.setActive(false)
        feedback.report(10L, 5L, longArrayOf(1L))
        assertEquals(listOf(1L), sessions[0].reports)
        feedback.setActive(true)
        feedback.report(10L, 5L, longArrayOf(1L))
        assertEquals(2, sessions.size)
        feedback.close(); feedback.close(); feedback.setActive(true)
        feedback.report(10L, 5L, longArrayOf(1L))
        assertEquals(listOf(1, 1), sessions.map { it.closes })
        assertEquals(listOf(1L), sessions[1].reports)
    }

    @Test fun failedPlatformCallReleasesSessionAndDisablesFurtherHints() {
        var closes = 0
        var attempts = 0
        val feedback = InteractionHintFeedback {
            attempts++
            object : InteractionHintSession {
                override fun target(nanos: Long) { }
                override fun report(nanos: Long) { throw UnsupportedOperationException() }
                override fun close() { closes++ }
            }
        }
        feedback.setActive(true); feedback.report(10L, 5L, longArrayOf(1L))
        feedback.setActive(false); feedback.setActive(true); feedback.close()
        assertEquals(1, closes)
        assertEquals(1, attempts)
    }

    @Test fun missingZeroAndOverflowMetricsAreNotFabricated() {
        assertNull(interactionUiWorkDuration(longArrayOf(-1L, 5L)))
        assertNull(interactionUiWorkDuration(longArrayOf(0L, 0L)))
        assertNull(interactionUiWorkDuration(longArrayOf(Long.MAX_VALUE, 1L)))
        val session = Session()
        val feedback = InteractionHintFeedback { session }
        feedback.setActive(true); feedback.report(-1L, 10L, longArrayOf(5L))
        assertTrue(session.reports.isEmpty())
        feedback.close()
    }

    @Test fun missedDeadlineNarrowsOnlyTheUiBudgetWithoutInventingActualWork() {
        assertEquals(InteractionUiFrameSample(2000L, 4000L),
            interactionUiFrameSample(8000L, 10000L, longArrayOf(4000L)))
        assertNull(interactionUiFrameSample(8000L, 12000L, longArrayOf(4000L)))
        assertNull(interactionUiFrameSample(8000L, 3000L, longArrayOf(4000L)))
        assertNull(interactionUiFrameSample(Long.MAX_VALUE, -1L, longArrayOf(1L)))
    }

    @Test fun unusableBudgetClosesTheSessionAndNextValidFrameRecalibrates() {
        val sessions = mutableListOf<Session>()
        val targets = mutableListOf<Long>()
        val feedback = InteractionHintFeedback { target ->
            targets += target
            Session().also(sessions::add)
        }
        feedback.setActive(true)
        feedback.report(16L, 10L, longArrayOf(4L))
        feedback.report(8L, 12L, longArrayOf(4L))
        assertEquals(1, sessions.single().closes)
        feedback.report(8L, 6L, longArrayOf(3L))
        assertEquals(listOf(10L, 5L), targets)
        feedback.close()
    }
}
