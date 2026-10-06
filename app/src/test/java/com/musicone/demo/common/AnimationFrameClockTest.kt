package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AnimationFrameClockTest {
    @Test fun selectedRatesAreBoundedByDisplayAndDoNotDrift() {
        for (display in listOf(60, 90, 120, 144)) {
            for (rate in AnimationFrameRate.entries) {
                val schedule = AnimationFrameSchedule()
                var count = 0
                repeat(display * 10) { frame ->
                    val time = frame * 1_000_000_000L / display
                    val accepted = schedule.accept(time, rate.fps)
                    // 同一帧中的其他组件必须收到完全相同的放行结果。
                    assertEquals(accepted, schedule.accept(time, rate.fps))
                    if (accepted) count++
                }
                val expected = if (rate.fps == 0) display * 10 else minOf(display, rate.fps) * 10
                assertEquals("display=$display rate=$rate", expected.toFloat(), count.toFloat(), 1f)
            }
        }
    }

    @Test fun independentClocksKeepRealTimestampsAndApplyChangesImmediately() = runBlocking {
        val upstream = BroadcastFrameClock()
        var uiRate = AnimationFrameRate.FPS_30
        val ui = AnimationFrameClock(upstream, { uiRate })
        val lyrics = AnimationFrameClock(upstream, { AnimationFrameRate.FPS_120 })
        val uiTimes = mutableListOf<Long>()
        val lyricTimes = mutableListOf<Long>()
        var dispatching = false
        val uiJob = launch {
            while (isActive) ui.withFrameNanos { assertTrue(dispatching); uiTimes += it }
        }
        val lyricJob = launch {
            while (isActive) lyrics.withFrameNanos { assertTrue(dispatching); lyricTimes += it }
        }
        repeat(120) { frame ->
            if (frame == 60) uiRate = AnimationFrameRate.FPS_60
            yield()
            dispatching = true
            upstream.sendFrame(frame * 1_000_000_000L / 120)
            dispatching = false
            yield()
        }
        uiJob.cancelAndJoin()
        lyricJob.cancelAndJoin()
        assertEquals(45, uiTimes.size)
        assertEquals(120, lyricTimes.size)
        assertTrue(lyricTimes.containsAll(uiTimes))
        assertTrue(uiTimes.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun longIdleAndUnknownSavedValuesDoNotCreateCatchUpFrames() {
        val schedule = AnimationFrameSchedule()
        assertTrue(schedule.accept(0, 30))
        assertTrue(schedule.accept(60_000_000_000L, 30))
        assertFalse(schedule.accept(60_008_333_333L, 30))
        assertTrue(schedule.accept(60_008_333_333L, 0))
        assertEquals(AnimationFrameRate.DISPLAY, AnimationFrameRate.fromStored(999))
    }
}
