package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricEntrancePlaybackScrollTest {
    @Test fun lateEntranceAndStationaryEntranceUseTheSameNaturalRowSpring() {
        val early = trajectory(.1f)
        val late = trajectory(.99f)
        val stationary = trajectory(1f)
        early.indices.forEach { frame ->
            assertEquals(stationary[frame], early[frame], .002f)
            assertEquals(stationary[frame], late[frame], .002f)
        }
        assertTrue(late.first() > 100f)
        assertEquals(0f, late.last(), .002f)
    }

    @Test fun entranceEndpointDoesNotFinishOrResetAnOngoingNaturalStep() = checkMotion {
        val shared = entrance(-178f)
        shared.moveEntrance(.99f)
        val job = step(shared, 1, 114f)
        frame()
        val before = 114f - scroll + motion.offsetPx(1) - shared.remainingOffset
        val velocity = motion.row(1).position.velocity
        shared.moveEntrance(1f)
        assertEquals(before, 114f - scroll + motion.offsetPx(1), .002f)
        assertEquals(velocity, motion.row(1).position.velocity, 0f)
        assertEquals(1, shared.playbackUsers)
        assertFalse(job.isCompleted)
        repeat(180) { frame() }
        assertTrue(job.isCompleted)
        assertEquals(0, shared.playbackUsers)
        assertEquals(114f, scroll, .002f)
    }

    @Test fun continuousLinesRetainTheirVisiblePositionAndVelocityAcrossEntranceEnd() = checkMotion {
        val shared = entrance(-90f)
        shared.moveEntrance(.4f)
        val first = step(shared, 1, 114f)
        repeat(6) { frame() }
        val before = (0..4).map { it * 114f - scroll + motion.offsetPx(it) }
        val velocities = (0..4).map { motion.row(it).position.velocity }
        first.cancelAndJoin()
        val next = step(shared, 2, 228f)
        (0..4).forEach {
            assertEquals(before[it], it * 114f - scroll + motion.offsetPx(it), .002f)
            assertEquals(velocities[it], motion.row(it).position.velocity, .002f)
        }
        shared.moveEntrance(1f)
        repeat(180) { frame() }
        assertTrue(next.isCompleted)
        assertEquals(228f, scroll, .002f)
    }

    @Test fun closingReversalMovesOnlyEntranceCompensation() = checkMotion {
        val shared = entrance(-178f)
        shared.moveEntrance(.2f)
        val job = step(shared, 1, 114f)
        repeat(6) { frame() }
        shared.moveEntrance(.8f)
        val natural = 114f - scroll - shared.remainingOffset
        val position = motion.row(1).position.value
        val velocity = motion.row(1).position.velocity
        shared.moveEntrance(.3f)
        assertEquals(natural, 114f - scroll - shared.remainingOffset, .002f)
        assertEquals(position, motion.row(1).position.value, 0f)
        assertEquals(velocity, motion.row(1).position.velocity, 0f)
        shared.moveEntrance(1f)
        repeat(180) { frame() }
        assertTrue(job.isCompleted)
    }

    @Test fun detachedEntranceCannotKeepScrollingOrHoldPlaybackOwnership() = checkMotion {
        val shared = entrance(-178f)
        shared.moveEntrance(.5f)
        val job = step(shared, 1, 114f)
        repeat(6) { frame() }
        val stopped = scroll
        shared.release()
        shared.moveEntrance(1f)
        repeat(3) { frame() }
        assertEquals(stopped, scroll, 0f)
        assertTrue(job.isCompleted)
        assertEquals(0, shared.playbackUsers)
    }

    @Test fun reducedMotionCompletesBothComponentsWithoutResiduals() = checkMotion(0f) {
        val shared = entrance(-178f)
        shared.moveEntrance(1f)
        val job = step(shared, 1, 114f)
        repeat(3) { frame() }
        assertTrue(job.isCompleted)
        assertEquals(114f, scroll, .002f)
        (0..4).forEach { assertEquals(0f, motion.offsetPx(it), .002f) }
    }

    private fun trajectory(start: Float): List<Float> {
        val values = mutableListOf<Float>()
        checkMotion {
            val shared = entrance(-178f)
            shared.moveEntrance(start)
            step(shared, 1, 114f)
            repeat(180) { tick ->
                shared.moveEntrance((start + (1f - start) * tick / 12f).coerceAtMost(1f))
                frame()
                values += 114f - scroll + motion.offsetPx(1) - shared.remainingOffset
            }
        }
        return values
    }

    private fun checkMotion(scale: Float = 1f, block: suspend Frames.() -> Unit) = runBlocking {
        withTimeout(10_000) {
            val clock = BroadcastFrameClock()
            withContext(clock + object : MotionDurationScale { override val scaleFactor = scale }) {
                val frames = Frames(this, clock)
                val driver = launch(start = CoroutineStart.UNDISPATCHED) { frames.motion.run() }
                try { frames.block() } finally { driver.cancelAndJoin() }
            }
        }
    }

    private class Frames(val scope: CoroutineScope, val clock: BroadcastFrameClock) {
        var time = 0L
        var scroll = 0f
        val motion = LyricPlaybackStepMotion(0) { time }
        init { (0..4).forEach { motion.row(it) } }

        fun entrance(offset: Float): LyricEntrancePlaybackScroll {
            scroll = -offset
            return LyricEntrancePlaybackScroll(0, offset, { delta -> scroll += delta; delta })
        }

        fun step(shared: LyricEntrancePlaybackScroll, index: Int, absoluteOffset: Float): Job {
            motion.consume(index, enabled = true, resumeInterrupted = true)
            return scope.launch(start = CoroutineStart.UNDISPATCHED) {
                runLyricNaturalPlaybackStep(motion, index, (0..4).toList(),
                    shared.playbackTravel(absoluteOffset - scroll), { shared.canScroll() }, shared::canScroll) { move ->
                    shared.scrollPlayback(move)
                }
            }
        }

        suspend fun frame() {
            yield()
            time += 16_666_667L
            clock.sendFrame(time)
            yield()
        }
    }
}
