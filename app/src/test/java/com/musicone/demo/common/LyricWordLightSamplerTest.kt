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
        sampler.update(0L, .8f, .8f, record = record)
        assertNotNull(sampler.update(10_000_000L, .9f, .88f, record = record))
        sampler.update(20_000_000L, 1f, 1f, record = record)
        assertEquals(listOf(.8f to .8f, 1f to 1f), samples)
        sampler.update(30_000_000L, .8f, .8f, record = record)
        assertEquals(.8f to .8f, samples.last())
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
        sampler.update(0L, .8f, .8f) { _, _ -> }
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

    @Test fun lightSamplingDoesNotThrottleWordScale() {
        var position = 0L
        val motion = LyricWordLightMotion()
        motion.configure(listOf(LyricHighlightRange(0, 1, LyricWord(100, 1000, "光"))),
            { position }, true, true, true)
        position = 100L
        val sampler = LyricWordLightSampler()
        var lastScale = motion.scale(0)
        var scaleUpdates = 0
        var recordings = 0
        repeat(30) {
            motion.advance(1f / 60)
            if (motion.scale(0) != lastScale) scaleUpdates++
            lastScale = motion.scale(0)
            sampler.update(motion.lightTimeNanos, .85f + it * .001f, .84f + it * .001f) { _, _ -> recordings++ }
        }
        assertTrue(scaleUpdates > 20)
        assertEquals(30, recordings)
    }

    @Test fun renderedScaleIsLimitedWhileUnderlyingMotionContinuesAtDisplayRate() {
        for (fps in listOf(60, 120)) {
            var position = 0L
            val motion = LyricWordLightMotion()
            motion.configure(listOf(LyricHighlightRange(0, 1, LyricWord(100, 1000, "光"))),
                { position }, true, true, true)
            position = 100L
            val sampler = LyricWordLightSampler(lowerEndpoint = .985f)
            var updates = 0
            var underlyingUpdates = 0
            var rendered = .985f
            var previous = motion.scale(0)
            val times = mutableListOf<Long>()
            repeat(fps / 2 + 2) {
                motion.advance(1f / fps)
                val scale = motion.scale(0)
                if (previous != scale) underlyingUpdates++
                previous = scale
                sampler.update(motion.lightTimeNanos, scale, scale) { left, right ->
                    assertEquals(left, right, 0f)
                    assertTrue(left >= rendered)
                    rendered = left
                    updates++
                    if (left != 1f) times.add(motion.lightTimeNanos)
                }?.let(motion::scheduleLightSample)
            }
            assertTrue(updates in 28..32)
            assertTrue(underlyingUpdates >= updates)
            if (fps > 60) assertTrue(underlyingUpdates > updates)
            times.zipWithNext().forEach { (before, after) -> assertTrue(after - before >= 16_666_666L) }
            assertEquals(1f, rendered, 0f)
        }
    }

    @Test fun scaleReverseAndReducedMotionReachEndpointsWithoutDelaying() {
        val sampler = LyricWordLightSampler(lowerEndpoint = .985f)
        var rendered = 1f
        val record: (Float, Float) -> Unit = { left, right -> assertEquals(left, right, 0f); rendered = left }
        sampler.update(0L, 1f, 1f, record = record)
        assertNotNull(sampler.update(1L, .99f, .99f, record = record))
        sampler.update(2L, .985f, .985f, record = record)
        assertEquals(.985f, rendered, 0f)
        sampler.update(3L, .994f, .994f, immediate = true, record = record)
        assertEquals(.994f, rendered, 0f)
        sampler.update(4L, 1f, 1f, record = record)
        assertEquals(1f, rendered, 0f)
    }
}
