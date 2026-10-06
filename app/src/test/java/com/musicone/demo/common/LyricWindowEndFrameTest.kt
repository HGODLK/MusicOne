package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.abs

@RunWith(Parameterized::class)
class LyricWindowEndFrameTest(
    private val travelPx: Float,
    private val fps: Int,
    private val direction: TrackTransitionDirection,
) {
    @Test fun singleTrackEndsWithoutAPixelSnap() = checkEndFrame(rapid = false)
    @Test fun cachedRapidSettlingEndsWithoutAPixelSnap() = checkEndFrame(rapid = true)

    private fun checkEndFrame(rapid: Boolean) = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val motion = LyricWindowMotion()
            motion.updateTravel(travelPx)
            val slot = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
            var time = 0L
            suspend fun frame() {
                yield()
                time += 1_000_000_000L / fps
                clock.sendFrame(time)
                yield()
            }
            if (rapid) {
                val preview = launch { motion.moveTo(slot * 2f) }
                repeat((fps * .35f).toInt()) { frame() }
                preview.cancelAndJoin()
            }
            val job = launch { motion.moveTo(slot) }
            var previous = motion.offset.value
            var finalStepPx = 0f
            var frames = 0
            while (!job.isCompleted && frames++ < fps * 3) {
                frame()
                finalStepPx = abs(motion.offset.value - previous) * travelPx
                previous = motion.offset.value
            }
            assertTrue("动画必须正常完成", job.isCompleted)
            assertEquals(-slot, motion.offset.value, 0f)
            assertTrue("终帧跳变 $finalStepPx px，距离 $travelPx px，帧率 $fps", finalStepPx <= .1f)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "距离={0}px 帧率={1} 方向={2}")
        fun cases(): List<Array<Any>> = listOf(720f, 1600f, 2960f).flatMap { height ->
            listOf(30, 60, 120).flatMap { fps ->
                TrackTransitionDirection.entries.map { direction -> arrayOf<Any>(height, fps, direction) }
            }
        }
    }
}
