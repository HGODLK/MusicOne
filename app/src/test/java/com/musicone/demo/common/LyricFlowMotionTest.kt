package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LyricFlowMotionTest {
    @Test fun flowHasTheSameElapsedTimeTrajectoryAtThirtySixtyAndOneTwentyHertz() {
        val results = listOf(30, 60, 120).map { fps ->
            var sample = LyricFlowSample(-.2f, -2f)
            repeat(fps / 2) { sample = advanceLyricFlow(sample, -3f, 1f / fps) }
            sample
        }
        results.forEach {
            assertEquals(results.first().position, it.position, .00001f)
            assertEquals(results.first().velocity, it.velocity, .0001f)
        }
    }

    @Test fun changingFlowTargetPreservesBothPositionAndVelocity() {
        val initial = LyricFlowSample(-.6f, -4f)
        for (target in listOf(-2f, 0f)) assertEquals(initial, advanceLyricFlow(initial, target, 0f))
        val reversing = advanceLyricFlow(initial, 0f, 1f / 120)
        assertTrue(reversing.position < initial.position)
        assertTrue(reversing.velocity > initial.velocity)
    }

    @Test fun manyCachedPagesStayAdjacentWithoutAccumulatingAFullQueueOfMotion() {
        for (direction in TrackTransitionDirection.entries) {
            var sample = LyricFlowSample(0f, 0f)
            var slot = 0f
            val sign = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
            repeat(80) {
                val next = nextLyricWindowSlot(slot, sample.position, direction, browsing = true)
                assertEquals(sign, next - slot, .00001f)
                slot = next
                repeat(12) { sample = advanceLyricFlow(sample, -slot, 1f / 120) }
                assertTrue("连续流动不积压整队歌词", abs(sample.position + slot) < 2f)
            }
        }
    }

    @Test fun flowNeverCrossesItsTargetThenScrollsBack() {
        for (sign in listOf(1f, -1f)) {
            var sample = LyricFlowSample(0f, sign * 50f)
            repeat(120) {
                sample = advanceLyricFlow(sample, sign, 1f / 120)
                assertTrue(sample.position * sign <= 1f)
            }
        }
    }

    @Test fun singleAnimationFlowsIntoRapidMotionThenSettlesWithoutPositionJumps() = withFrames {
        for (sign in listOf(1f, -1f)) {
            val motion = LyricWindowMotion()
            motion.updateTravel(2_960f)
            motion.request(this, sign)
            frames(8)
            val initial = motion.offset.value
            motion.request(this, 2f * sign, browsing = true)
            assertEquals(initial, motion.offset.value, 0f)
            frames(6)
            assertTrue((motion.offset.value - initial) * sign < 0f)
            val continuing = motion.offset.value
            motion.request(this, 3f * sign, browsing = true)
            assertEquals(continuing, motion.offset.value, 0f)
            frames(12)
            val stopping = motion.offset.value
            motion.request(this, 3f * sign)
            assertEquals(stopping, motion.offset.value, 0f)
            frames(100)
            assertTrue(motion.settledAt(3f * sign))
        }
    }

    @Test fun resumedInputAndReverseDirectionDoNotRestartTheWindowAtZero() = withFrames {
        val motion = LyricWindowMotion()
        motion.request(this, 2f, browsing = true)
        frames(15)
        motion.request(this, 2f)
        frames(4)
        val reversing = motion.offset.value
        motion.request(this, 0f, browsing = true)
        assertEquals(reversing, motion.offset.value, 0f)
        frames(24)
        assertTrue(motion.offset.value > reversing)
        motion.request(this, 0f)
        frames(100)
        assertTrue(motion.settledAt(0f))
    }

    @Test fun reducedMotionAndHiddenLyricsStillReleaseTheFinalHandoff() = withFrames {
        val options = ExperiencePreferences.options
        try {
            ExperiencePreferences.update(options.copy(reduceMotion = true))
            val motion = LyricWindowMotion()
            motion.request(this, 3f, browsing = true)
            frames(4)
            assertEquals(-3f, motion.offset.value, 0f)
            motion.request(this, 3f)
            frames(4)
            assertTrue(motion.settledAt(3f))
            motion.request(this, -2f, browsing = true)
            frames(2)
            motion.show(-2f)
            assertTrue(motion.settledAt(-2f))
        } finally { ExperiencePreferences.update(options) }
    }

    private fun withFrames(block: suspend TestFrames.() -> Unit) = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) { TestFrames(this, clock).block() }
    }
    private class TestFrames(scope: CoroutineScope, private val clock: BroadcastFrameClock) : CoroutineScope by scope {
        private var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) { yield(); time += 16_666_667; clock.sendFrame(time); yield() }
        }
    }
}
