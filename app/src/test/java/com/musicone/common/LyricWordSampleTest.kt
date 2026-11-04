package com.musicone

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class LyricWordSampleTest {
    @Test fun inPlaceSamplesMatchOriginalTrajectoryAcrossFrameRatesAndReversals() {
        for (fps in listOf(15, 30, 60, 120)) {
            var previous = LyricFlowSample(0f, 0f)
            val sample = LyricWordSample(0f)
            for (frame in 0..fps * 6) {
                val target = when {
                    frame < fps -> .7f
                    frame < fps * 2 -> .2f
                    frame < fps * 3 -> 1f
                    else -> 0f
                }
                val seconds = 1f / fps
                previous = if (abs(previous.position - target) <= .0001f && abs(previous.velocity) <= .002f) {
                    LyricFlowSample(target, 0f)
                } else advanceLyricFlow(previous, target, seconds, 22f).let {
                    val bounded = it.position.coerceIn(0f, 1f)
                    if (bounded != it.position) LyricFlowSample(bounded, 0f) else it
                }
                sample.follow(target, seconds, false)
                assertEquals(previous.position, sample.position, 0f)
                assertEquals(previous.velocity, sample.velocity, 0f)
            }
        }
    }

    @Test fun reducedMotionAndSettledSamplesHaveExactEndpoints() {
        val sample = LyricWordSample(.35f, 12f)
        assertTrue(sample.follow(1f, 0f, true))
        assertEquals(1f, sample.position, 0f)
        assertEquals(0f, sample.velocity, 0f)
        assertFalse(sample.follow(1f, 1f / 120, false))
        assertFalse(sample.moving(1f))
    }
}
