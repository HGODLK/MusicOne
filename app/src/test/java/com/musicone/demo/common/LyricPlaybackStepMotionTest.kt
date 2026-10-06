package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricPlaybackStepMotionTest {
    @Test fun playbackStyleKeepsOneCurveAndTunesSeekByDistance() {
        assertEquals(200f, lyricPlaybackMotionStiffness(
            900f,
            LyricPlaybackMotionPurpose.PLAYBACK,
        ), .0001f)
        assertEquals(260f, lyricPlaybackMotionStiffness(
            80f,
            LyricPlaybackMotionPurpose.SEEK,
        ), .0001f)
        assertEquals(220f, lyricPlaybackMotionStiffness(
            300f,
            LyricPlaybackMotionPurpose.SEEK,
        ), .0001f)
        assertEquals(185f, lyricPlaybackMotionStiffness(
            900f,
            LyricPlaybackMotionPurpose.SEEK,
        ), .0001f)
        assertEquals(210f, lyricPlaybackMotionStiffness(
            300f,
            LyricPlaybackMotionPurpose.ALIGNMENT,
        ), .0001f)
    }

    @Test fun manualBrowsingDoesNotBecomeAnAutomaticStepOnReturn() {
        val motion = LyricPlaybackStepMotion(0)
        assertEquals(LyricPlaybackStepChange.RESET, motion.consume(1, enabled = false))
        assertEquals(LyricPlaybackStepChange.NONE, motion.consume(1, enabled = true))
        assertEquals(LyricPlaybackStepChange.ANIMATE, motion.consume(2, enabled = true))
        assertEquals(LyricPlaybackStepChange.RESET, motion.consume(5, enabled = true))
    }

    @Test fun firstFrameMovesEveryRowAndTransfersEmphasisTogether() {
        listOf(30, 60, 90, 120).forEach { fps -> checkMotion {
            val before = (0..4).map(::screenY)
            val job = step(1, 100f)
            (0..4).forEach { assertEquals(before[it], screenY(it), .001f) }
            frame(1_000_000_000L / fps)
            (0..4).forEach { assertTrue("第 $it 行应在首个有效帧开始移动", screenY(it) < before[it]) }
            assertTrue(motion.row(0).focus.value < 1f)
            assertTrue(motion.row(0).opacity.value < 1f)
            assertTrue(motion.row(1).focus.value > 0f)
            assertTrue(motion.row(1).opacity.value > .6f)
            assertTrue(motion.row(1).blur.value < 2f)
            assertTrue(motion.offsetPx(3) > motion.offsetPx(2))
            frames(150)
            assertTrue(job.isCompleted)
            assertEquals(100f, actualScroll, .001f)
            (0..4).forEach { assertEquals(0f, motion.offsetPx(it), .001f) }
        } }
    }

    @Test fun rapidStepPreservesScreenPositionAndVelocityWithoutAnIdleFrame() = checkMotion {
        var job = step(1, 100f)
        frames(8)
        val before = (0..4).map(::screenY)
        val velocities = (0..4).map { motion.row(it).position.velocity }
        val focus = motion.row(1).focus.value
        job.cancelAndJoin()
        job = step(2, 200f - actualScroll)
        (0..4).forEach {
            assertEquals(before[it], screenY(it), .001f)
            assertEquals(velocities[it], motion.row(it).position.velocity, .001f)
        }
        assertEquals(focus, motion.row(1).focus.value, .001f)
        frame()
        (0..4).forEach { assertTrue("连续切句不能额外停留一帧", screenY(it) < before[it]) }
        frames(150)
        assertTrue(job.isCompleted)
        assertEquals(200f, actualScroll, .001f)
        assertEquals(1f, motion.row(2).focus.value, .001f)
        assertEquals(0f, motion.row(1).focus.value, .001f)
    }

    @Test fun manualTakeoverKeepsVisibleOffsetsAndFocusThenSettles() = checkMotion {
        val job = step(1, 180f)
        frames(6)
        val before = (0..4).map(::screenY)
        val focus = motion.row(1).focus.value
        job.cancelAndJoin()
        motion.consume(3, enabled = false)
        motion.reset()
        (0..4).forEach { motion.emphasize(it, 3, animate = true, playback = false) }
        (0..4).forEach { assertEquals(before[it], screenY(it), .001f) }
        assertEquals(focus, motion.row(1).focus.value, .001f)
        frames(150)
        (0..4).forEach { assertEquals(0f, motion.offsetPx(it), .001f) }
        assertEquals(1f, motion.row(3).focus.value, .001f)
        assertEquals(0f, motion.row(1).focus.value, .001f)
    }

    @Test fun actualScrollBoundaryDoesNotLeavePermanentOffsets() = checkMotion {
        val job = step(1, 180f) { requested -> requested.coerceAtMost(70f - actualScroll) }
        frames(150)
        assertTrue(job.isCompleted)
        assertEquals(70f, actualScroll, .001f)
        (0..4).forEach { assertEquals(0f, motion.offsetPx(it), .001f) }
    }

    @Test fun idleTimeIsNotReplayedIntoTheNextStep() = checkMotion {
        val first = step(1, 100f)
        frames(150)
        assertTrue(first.isCompleted)
        assertFalse(clock.hasAwaiters)
        time += 10_000_000_000L
        val second = step(2, 100f)
        frame()
        assertTrue(actualScroll > 100f && actualScroll < 110f)
        frames(150)
        assertTrue(second.isCompleted)
    }

    @Test fun repeatedEmphasisUpdatesDoNotRestartTheAnimation() = checkMotion {
        val job = step(1, 100f)
        repeat(150) {
            (0..4).forEach { motion.emphasize(it, 1, animate = true, playback = true) }
            frame()
        }
        assertTrue(job.isCompleted)
        assertEquals(1f, motion.row(1).focus.value, .001f)
        assertEquals(0f, motion.row(0).focus.value, .001f)
        assertFalse(motion.row(1).running)
    }

    @Test fun newlyVisibleRowsJoinWithoutAnInitialOffsetAndReleasedRowsDoNotBlockCompletion() = checkMotion {
        val job = step(1, 180f)
        frames(5)
        val row = motion.row(5)
        val before = screenY(5)
        assertEquals(0f, motion.offsetPx(5), .001f)
        frame()
        assertTrue(screenY(5) < before)
        motion.release(5, row)
        frames(150)
        assertTrue(job.isCompleted)
    }

    @Test fun differentFrameRatesAndMissedFramesReachTheSamePositionsAtTheSameTime() {
        val positions = mutableListOf<List<Float>>()
        listOf(10_000_000L, 20_000_000L, 40_000_000L, 200_000_000L).forEach { interval ->
            checkMotion {
                val job = step(1, 240f)
                repeat((200_000_000L / interval).toInt()) { frame(interval) }
                positions += (0..4).map(::screenY)
                frames(150)
                assertTrue(job.isCompleted)
            }
        }
        positions.drop(1).forEach { actual ->
            positions.first().indices.forEach { assertEquals(positions.first()[it], actual[it], .001f) }
        }
    }

    @Test fun disabledSystemAnimationsCompleteOnTheFirstFrame() = checkMotion(scale = 0f) {
        val job = step(1, 180f)
        frame()
        assertTrue(job.isCompleted)
        assertEquals(180f, actualScroll, .001f)
        assertEquals(1f, motion.row(1).focus.value, .001f)
        (0..4).forEach { assertEquals(0f, motion.offsetPx(it), .001f) }
    }

    @Test fun reducedMotionCompletesWithoutResidualOffsets() {
        val previous = ExperiencePreferences.options
        try {
            ExperiencePreferences.update(previous.copy(reduceMotion = true))
            checkMotion {
                val job = step(1, 180f)
                frame()
                assertTrue(job.isCompleted)
                assertEquals(180f, actualScroll, .001f)
                assertEquals(1f, motion.row(1).focus.value, .001f)
                (0..4).forEach { assertEquals(0f, motion.offsetPx(it), .001f) }
            }
        } finally {
            ExperiencePreferences.update(previous)
        }
    }

    private fun checkMotion(scale: Float = 1f, block: suspend MotionFrames.() -> Unit) = runBlocking {
        withTimeout(10_000) {
            val clock = BroadcastFrameClock()
            val durationScale = object : MotionDurationScale { override val scaleFactor = scale }
            withContext(clock + durationScale) {
                val frames = MotionFrames(this, clock)
                val driver = launch(start = CoroutineStart.UNDISPATCHED) { frames.motion.run() }
                try { frames.block() } finally { driver.cancelAndJoin() }
            }
        }
    }

    private class MotionFrames(val scope: CoroutineScope, val clock: BroadcastFrameClock) {
        var time = 0L
        var actualScroll = 0f
        val motion = LyricPlaybackStepMotion(0) { time }
        init { (0..4).forEach { motion.row(it) } }

        fun step(current: Int, travel: Float, consume: (Float) -> Float = { it }): Job {
            motion.consume(current, enabled = true)
            return scope.launch(start = CoroutineStart.UNDISPATCHED) {
                motion.move(current, (0..4).toList(), travel) {
                    consume(it).also { consumed -> actualScroll += consumed }
                }
            }
        }

        fun screenY(index: Int) = index * 100f - actualScroll + motion.offsetPx(index)

        suspend fun frame(elapsed: Long = 16_666_667L) {
            yield()
            time += elapsed
            clock.sendFrame(time)
            yield()
        }

        suspend fun frames(count: Int) { repeat(count) { frame() } }
    }
}
