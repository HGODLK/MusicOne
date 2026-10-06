package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class LyricWindowMotionTest {
    @Test fun nextSlidesBothPagesUntilTheOldLyricsAreOutside() = checkSingle(TrackTransitionDirection.NEXT)
    @Test fun previousSlidesBothPagesUntilTheOldLyricsAreOutside() = checkSingle(TrackTransitionDirection.PREVIOUS)
    @Test fun rapidNextPreservesActualPositionAndVelocity() = checkInterrupted(TrackTransitionDirection.NEXT)
    @Test fun reversingToTheOriginalTrackPreservesActualPositionAndVelocity() = checkInterrupted(TrackTransitionDirection.PREVIOUS)
    @Test fun cachedRapidNextKeepsThreePagesAdjacent() = checkRapid(TrackTransitionDirection.NEXT)
    @Test fun cachedRapidPreviousKeepsThreePagesAdjacent() = checkRapid(TrackTransitionDirection.PREVIOUS)
    @Test fun rapidNextDoesNotOvershootAndScrollBackAtTheEnd() = checkNoOvershoot(TrackTransitionDirection.NEXT)
    @Test fun rapidPreviousDoesNotOvershootAndScrollBackAtTheEnd() = checkNoOvershoot(TrackTransitionDirection.PREVIOUS)

    @Test fun manualPreviewHandoffKeepsTheNaturalSwitchTrajectoryInBothDirections() = withFrames {
        for (direction in TrackTransitionDirection.entries) {
            val slot = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
            val natural = LyricWindowMotion()
            val manual = LyricWindowMotion()
            natural.request(this, slot)
            manual.request(this, slot)
            repeat(90) { frame ->
                // 手动预览在 400ms 提交音频、720ms 撤除标记，不能因此更换曲线或重启尾段。
                if (frame == 24 || frame == 43) manual.request(this, slot)
                frames(1)
                assertEquals("手动交接第 $frame 帧位置", natural.offset.value, manual.offset.value, .00001f)
                assertEquals("手动交接第 $frame 帧速度", natural.offset.velocity, manual.offset.velocity, .00001f)
            }
        }
    }

    @Test fun reversingBeforeTheIncomingPageArrivesDoesNotPassTheNewTarget() = withFrames {
        val motion = LyricWindowMotion()
        var job = launch { motion.moveTo(2f) }
        frames(5)
        job.cancelAndJoin()
        job = launch { motion.moveTo(1f) }
        repeat(120) {
            frames(1)
            assertTrue(motion.offset.value >= -1f)
        }
        assertTrue(job.isCompleted)
        assertEquals(-1f, motion.offset.value, .0001f)
    }

    @Test fun browsingTheAlreadyCenteredWindowDoesNotMoveItAwayFromTheStart() = withFrames {
        val motion = LyricWindowMotion()
        val job = launch { motion.moveTo(0f) }
        frames(3)
        assertTrue(job.isCompleted)
        assertEquals(0f, motion.offset.value, 0f)
        assertEquals(0f, lyricWindowSettleVelocity(2.4f, 0f), 0f)
    }

    @Test fun removingTheRapidPreviewDoesNotCancelOrRestartSettling() = withFrames {
        val motion = LyricWindowMotion()
        motion.request(this, 2f)
        frames(24)
        motion.request(this, 2f)
        frames(19)
        val position = motion.offset.value
        val velocity = motion.offset.velocity
        // 对应真实播放接棒后，连点预览标记在 320ms 消失。
        motion.request(this, 2f)
        assertEquals(position, motion.offset.value, 0f)
        assertEquals(velocity, motion.offset.velocity, 0f)
        frames(120)
        assertEquals(-2f, motion.offset.value, .0001f)
        assertFalse(motion.offset.isRunning)
    }

    private fun checkNoOvershoot(direction: TrackTransitionDirection) = withFrames {
        val motion = LyricWindowMotion()
        val slot = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
        var job = launch { motion.moveTo(slot) }
        frames(24)
        job.cancelAndJoin()
        job = launch { motion.moveTo(slot) }
        repeat(120) {
            frames(1)
            assertTrue("歌词不能越过终点后反向滚回", motion.offset.value * slot >= -1.0001f)
        }
        assertTrue(job.isCompleted)
        assertEquals(-slot, motion.offset.value, .0001f)
    }

    @Test fun cleanupWaitsForTheCorrectViewportEdge() {
        assertFalse(lyricWindowOutside(-.999f, TrackTransitionDirection.NEXT))
        assertTrue(lyricWindowOutside(-1f, TrackTransitionDirection.NEXT))
        assertFalse(lyricWindowOutside(1f, TrackTransitionDirection.NEXT))
        assertFalse(lyricWindowOutside(.999f, TrackTransitionDirection.PREVIOUS))
        assertTrue(lyricWindowOutside(1f, TrackTransitionDirection.PREVIOUS))
        assertFalse(lyricWindowOutside(-1f, TrackTransitionDirection.PREVIOUS))
    }

    @Test fun cachedRapidReversalAndSettlingUseTheSameWindowPositions() = withFrames {
        val motion = LyricWindowMotion()
        var job: Job? = null
        for (slot in listOf(1f, 2f, 1f, 0f)) {
            val before = motion.offset.value
            job?.cancelAndJoin()
            job = launch { motion.moveTo(slot) }
            yield()
            assertEquals(before, motion.offset.value, .0001f)
            frames(5)
        }
        val beforeSettle = motion.offset.value
        job?.cancelAndJoin()
        job = launch { motion.moveTo(0f) }
        yield()
        assertEquals(beforeSettle, motion.offset.value, .0001f)
        frames(120)
        assertTrue(job.isCompleted)
        assertEquals(0f, motion.offset.value, .0001f)
    }

    @Test fun reducedMotionAndHiddenLyricsReachTheCorrectSlot() = withFrames {
        val previous = ExperiencePreferences.options
        try {
            ExperiencePreferences.update(previous.copy(reduceMotion = true))
            val motion = LyricWindowMotion()
            for (direction in TrackTransitionDirection.entries) {
                val slot = if (direction == TrackTransitionDirection.NEXT) 3f else -3f
                val job = launch { motion.moveTo(slot) }
                frames(2)
                assertTrue(job.isCompleted)
                assertEquals(-slot, motion.offset.value, .0001f)
            }
            motion.show(8f)
            assertEquals(-8f, motion.offset.value, .0001f)
        } finally {
            ExperiencePreferences.update(previous)
        }
    }

    private fun checkSingle(direction: TrackTransitionDirection) = withFrames {
        val sign = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
        val motion = LyricWindowMotion()
        val job = launch { motion.moveTo(sign) }
        frames(15)
        // 原有 240ms 淡出此时已经结束，但歌词仍在屏内，不能提前撤层。
        assertTrue(abs(motion.offset.value) < 1f)
        assertFalse(lyricWindowOutside(motion.offset.value, direction))
        for (height in listOf(720f, 1600f)) {
            val oldY = motion.offset.value * height
            val newY = (motion.offset.value + sign) * height
            assertEquals(sign * height, newY - oldY, .001f)
        }
        frames(120)
        assertTrue(job.isCompleted)
        assertEquals(-sign, motion.offset.value, .0001f)
        assertTrue(lyricWindowOutside(motion.offset.value, direction))
    }

    private fun checkInterrupted(direction: TrackTransitionDirection) = withFrames {
        val motion = LyricWindowMotion()
        var job = launch { motion.moveTo(1f) }
        frames(8)
        val offset = motion.offset.value
        val velocity = motion.offset.velocity
        assertTrue(offset < 0f && offset > -1f)
        job.cancelAndJoin()
        val slot = if (direction == TrackTransitionDirection.NEXT) 2f else 0f
        job = launch { motion.moveTo(slot) }
        yield()
        assertEquals(offset, motion.offset.value, .0001f)
        frames(1)
        assertEquals(velocity, motion.offset.velocity, .001f)
        frames(120)
        assertTrue(job.isCompleted)
        assertEquals(-slot, motion.offset.value, .0001f)
    }

    private fun checkRapid(direction: TrackTransitionDirection) = withFrames {
        val sign = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
        val motion = LyricWindowMotion()
        var job: Job? = null
        for (page in 1..3) {
            val before = motion.offset.value
            job?.cancelAndJoin()
            job = launch { motion.moveTo(page * sign) }
            yield()
            assertEquals(before, motion.offset.value, .0001f)
            frames(6)
            assertTrue((motion.offset.value - before) * sign < 0f)
            val positions = (0..page).map { motion.offset.value + it * sign }
            positions.zipWithNext().forEach { (a, b) -> assertEquals(sign, b - a, .0001f) }
        }
        job?.cancelAndJoin()
        job = launch { motion.moveTo(3f * sign) }
        frames(120)
        assertTrue(job.isCompleted)
        assertEquals(-3f * sign, motion.offset.value, .0001f)
        for (oldPage in 0..2) assertTrue(lyricWindowOutside(motion.offset.value + oldPage * sign, direction))
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
