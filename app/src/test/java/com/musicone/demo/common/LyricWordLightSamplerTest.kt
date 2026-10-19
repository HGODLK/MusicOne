package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricWordLightSamplerTest {
    @Test fun intermediateLightCapsAtSixtyFpsWithoutUpsamplingSlowerDisplays() {
        for (fps in listOf(24, 30, 60, 120, 240)) {
            val sampler = LyricWordLightSampler()
            val recorded = mutableListOf<Long>()
            for (frame in 0..fps) {
                val time = frame * 1_000_000_000L / fps
                sampler.update(time, .85f + frame * .05f / fps, .84f + frame * .05f / fps) { _, _ ->
                    recorded.add(time)
                }
            }
            assertEquals(minOf(fps, 60) + 1, recorded.size)
            recorded.zipWithNext().forEach { (before, after) -> assertTrue(after - before >= 16_666_666L) }
        }
    }

    @Test fun shortWordsAndDisablingReachExactEndpointsWithoutWaiting() {
        val sampler = LyricWordLightSampler()
        val samples = mutableListOf<Pair<Float, Float>>()
        val record: (Float, Float) -> Unit = { left, right -> samples.add(left to right) }
        sampler.update(0L, .7f, .7f, record = record)
        assertNotNull(sampler.update(10_000_000L, .9f, .88f, record = record))
        sampler.update(20_000_000L, 1f, 1f, record = record)
        assertEquals(listOf(.7f to .7f, 1f to 1f), samples)
        sampler.update(30_000_000L, .7f, .7f, record = record)
        assertEquals(.7f to .7f, samples.last())
    }

    @Test fun reducedMotionSamplesImmediatelyAndUnchangedValuesDoNotRerecord() {
        val sampler = LyricWordLightSampler()
        var count = 0
        sampler.update(0L, .9f, .9f) { _, _ -> count++ }
        sampler.update(1L, .92f, .92f, immediate = true) { _, _ -> count++ }
        repeat(120) { sampler.update(it * 10_000_000L, .92f, .92f) { _, _ -> count++ } }
        assertEquals(2, count)
    }

    @Test fun pendingSampleKeepsClockAliveUntilPausedIntermediateValueIsRecorded() {
        val motion = LyricWordLightMotion()
        val ranges = listOf(LyricHighlightRange(0, 1, LyricWord(0, 1000, "光")))
        motion.configure(ranges, { 500L }, true, false, true)
        assertFalse(motion.running)
        val sampler = LyricWordLightSampler()
        sampler.update(0L, .7f, .7f) { _, _ -> }
        var recorded = false
        val deadline = sampler.update(1L, motion.alpha(0, 0f), motion.alpha(0, 1f)) { _, _ -> recorded = true }
        motion.scheduleLightSample(requireNotNull(deadline))
        assertTrue(motion.running)
        val before = motion.renderRevision
        repeat(7) { motion.advance(1f / 60) }
        assertTrue(motion.renderRevision > before)
        assertFalse(motion.running)
        sampler.update(motion.lightTimeNanos, motion.alpha(0, 0f), motion.alpha(0, 1f)) { _, _ -> recorded = true }
        assertTrue(recorded)
    }

}
