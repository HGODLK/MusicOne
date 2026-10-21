package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaybackPreloadTimingTest {
    private fun stall(timing: PlaybackPreloadTiming, start: Long, low: Boolean = true) {
        timing.observe(start, true, false, true, false)
        timing.observe(start + 100, false, true, true, false)
        timing.observe(start + 2_200, false, true, true, low)
        timing.observe(start + 2_300, true, false, true, false)
    }

    @Test fun threeConfirmedEpisodesSwitchOnlyOnRecoveryAndStartANewStableClock() {
        val timing = PlaybackPreloadTiming()
        timing.foregroundChanged(true)
        assertEquals(280L, timing.downloadDelayMs(0))
        assertEquals(2_000L, timing.downloadDelayMs(1))
        stall(timing, 0)
        stall(timing, 4_000)
        assertFalse(timing.conservative)
        stall(timing, 8_000)
        assertTrue(timing.conservative)
        assertEquals(3, timing.bufferingCount)
        assertEquals(0L, timing.elapsedMs(10_300))
        assertEquals(2_999L, timing.elapsedMs(13_299))
        assertEquals(3_000L, timing.downloadDelayMs(0))
        assertEquals(10_000L, timing.downloadDelayMs(1))
        assertNull(timing.downloadDelayMs(2))
    }

    @Test fun startupBufferingAndDecoderWaitsNeverCount() {
        val timing = PlaybackPreloadTiming()
        timing.observe(0, false, true, true, true)
        timing.observe(5_000, true, false, true, false)
        repeat(3) { stall(timing, 6_000L + it * 4_000L, low = false) }
        assertEquals(0, timing.bufferingCount)
    }

    @Test fun repeatedBufferingSamplesCountAsOneEpisode() {
        val timing = PlaybackPreloadTiming()
        timing.observe(0, true, false, true, false)
        timing.observe(100, false, true, true, false)
        repeat(30) { timing.observe(2_200L + it * 100L, false, true, true, true) }
        assertEquals(0, timing.bufferingCount)
        timing.observe(6_000, true, false, true, false)
        assertEquals(1, timing.bufferingCount)
    }

    @Test fun seekPauseQualitySwitchAndTransferErrorsDiscardTheCandidate() {
        for (interruption in 0..2) {
            val timing = PlaybackPreloadTiming()
            timing.observe(0, true, false, true, false)
            timing.observe(100, false, true, true, false)
            timing.observe(2_200, false, true, true, true)
            when (interruption) {
                0 -> timing.newTrack()
                1 -> timing.observe(2_300, false, true, false, true)
                else -> timing.observe(2_300, false, true, true, true, transferFailed = true)
            }
            timing.observe(3_000, true, false, true, false)
            assertEquals(0, timing.bufferingCount)
        }
    }

    @Test fun foregroundTransitionResetsButRepeatedVisibleEventsAndTrackChangesDoNot() {
        val timing = PlaybackPreloadTiming()
        assertTrue(timing.foregroundChanged(true))
        repeat(3) { stall(timing, it * 4_000L) }
        assertFalse(timing.foregroundChanged(true))
        timing.newTrack()
        assertTrue(timing.conservative)
        timing.foregroundChanged(false)
        assertTrue(timing.foregroundChanged(true))
        assertEquals(0, timing.bufferingCount)
        assertEquals(280L, timing.downloadDelayMs(0))
        timing.observe(20_000, true, false, true, false)
        assertEquals(279L, timing.elapsedMs(20_279))
    }

    @Test fun conservativeDelayRestartsAfterEveryPauseOrRebuffer() {
        val timing = PlaybackPreloadTiming()
        repeat(3) { stall(timing, it * 4_000L) }
        timing.observe(25_000, false, false, false, false)
        timing.observe(30_000, true, false, true, false)
        assertEquals(0L, timing.elapsedMs(30_000))
        timing.observe(31_000, false, true, true, false)
        timing.observe(33_000, true, false, true, false)
        assertEquals(0L, timing.elapsedMs(33_000))
        assertTrue(timing.conservative)
    }

    @Test fun briefWaitsDoNotCountEvenWithASlowSample() {
        val timing = PlaybackPreloadTiming()
        timing.observe(0, true, false, true, false)
        timing.observe(100, false, true, true, true)
        timing.observe(500, true, false, true, false)
        assertEquals(0, timing.bufferingCount)
    }
}
