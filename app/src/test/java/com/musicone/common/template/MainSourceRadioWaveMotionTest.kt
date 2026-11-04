package com.musicone

import org.junit.Assert.*
import org.junit.Test

class MainSourceRadioWaveMotionTest {
    @Test fun startingAndStoppingKeepTheirPhaseAndReachTheirSpeedAtThePlannedTime() {
        val motion = MainSourceRadioWaveMotion(false, 12f)
        motion.request(true)
        assertEquals(12f, motion.phase, 0f)
        assertEquals(0f, motion.velocity, 0f)
        motion.advance(.420)
        assertEquals(16.2f, motion.phase, .001f)
        assertEquals(20f, motion.velocity, .001f)
        motion.request(false)
        assertEquals(20f, motion.velocity, .001f)
        motion.advance(.520)
        assertEquals(21.4f, motion.phase, .001f)
        assertEquals(0f, motion.velocity, .001f)
        assertFalse(motion.running)
        motion.advance(10.0)
        assertEquals(21.4f, motion.phase, .001f)
    }

    @Test fun resumingHalfwayThroughTheBrakeContinuesFromTheActualSpeed() {
        val motion = MainSourceRadioWaveMotion(true, 30f)
        motion.request(false)
        motion.advance(.260)
        val phase = motion.phase
        assertEquals(10f, motion.velocity, .001f)
        motion.request(true)
        assertEquals(phase, motion.phase, 0f)
        assertEquals(10f, motion.velocity, .001f)
        motion.advance(.210)
        assertEquals(15f, motion.velocity, .001f)
        assertTrue(motion.phase > phase)
        motion.advance(.210)
        assertEquals(20f, motion.velocity, .001f)
    }

    @Test fun repeatedStateUpdatesDoNotRestartTheRampAndQuickChangesNeverReverse() {
        val motion = MainSourceRadioWaveMotion(false)
        motion.request(true)
        motion.advance(.210)
        motion.request(true)
        motion.advance(.210)
        assertEquals(20f, motion.velocity, .001f)
        for (step in 0..19) {
            val phase = motion.phase
            val velocity = motion.velocity
            motion.request(step % 2 == 0)
            assertEquals(phase, motion.phase, 0f)
            assertEquals(velocity, motion.velocity, 0f)
            motion.advance(.025)
            assertTrue(motion.phase >= phase)
            assertTrue(motion.velocity in 0f..20f)
        }
    }

    @Test fun lowAndHighFrameRatesAndDelayedFramesIntegrateTheSameTravel() {
        val reference = MainSourceRadioWaveMotion(false).apply {
            request(true); advance(.700); request(false); advance(.520)
        }
        for (fps in listOf(30, 60, 90, 120)) {
            val motion = MainSourceRadioWaveMotion(false)
            motion.request(true)
            advanceFrames(motion, .700, 1.0 / fps)
            motion.request(false)
            advanceFrames(motion, .520, 1.0 / fps)
            assertEquals(reference.phase, motion.phase, .003f)
            assertEquals(0f, motion.velocity, .001f)
            assertFalse(motion.running)
        }
        val delayed = MainSourceRadioWaveMotion(false).apply {
            request(true); advance(.016); advance(.180); advance(.504)
            request(false); advance(.030); advance(.400); advance(.090)
        }
        assertEquals(reference.phase, delayed.phase, .003f)
    }

    @Test fun cruiseWrapsWithoutChangingSpeedAndInvisibleTimeIsNotReplayed() {
        val motion = MainSourceRadioWaveMotion(true, 350f)
        motion.advance(1.0)
        assertEquals(10f, motion.phase, .001f)
        motion.settle(false)
        assertEquals(10f, motion.phase, .001f)
        assertFalse(motion.running)
        motion.settle(true)
        assertEquals(10f, motion.phase, .001f)
        assertEquals(20f, motion.velocity, 0f)
        motion.advance(18.0)
        assertEquals(10f, motion.phase, .001f)
    }

    @Test fun recommendationRefillKeepsTheCurrentPlaybackVisualState() {
        assertEquals(MainSourceRadioIndicatorState.PLAYING, mainSourceRadioIndicatorState(true, true, true))
        assertEquals(MainSourceRadioIndicatorState.PAUSED, mainSourceRadioIndicatorState(true, false, true))
        assertEquals(MainSourceRadioIndicatorState.LOADING, mainSourceRadioIndicatorState(false, false, true))
        assertEquals(MainSourceRadioIndicatorState.IDLE, mainSourceRadioIndicatorState(false, false, false))
    }

    private fun advanceFrames(motion: MainSourceRadioWaveMotion, seconds: Double, frame: Double) {
        var remaining = seconds
        while (remaining > 1e-9) {
            val step = minOf(frame, remaining)
            motion.advance(step)
            remaining -= step
        }
    }
}
