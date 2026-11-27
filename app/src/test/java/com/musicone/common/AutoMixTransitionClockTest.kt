package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Test

/** 过渡时钟的插值与边界，以及直连解码的限速计算。 */
class AutoMixTransitionClockTest {
    private val a = autoMixTestTrack("a")
    private val b = autoMixTestResolved(autoMixTestTrack("b"))

    @Test fun positionsInterpolateTheTenMillisecondMaps() {
        var now = 5_005L
        val clock = AutoMixTransitionClock(autoMixTestPrepared(a, b)) { now }
        assertEquals(5_005L, clock.clockMs())
        assertEquals(5_005L, clock.toPositionMs())
        assertEquals(5_005L, clock.fromPositionMs())
        now = 0L
        assertEquals(0f, clock.visualProgress(0L))
    }

    @Test fun clockIsClampedToThePlanAndAStaysWithinItsDuration() {
        var now = -50L
        val clock = AutoMixTransitionClock(autoMixTestPrepared(autoMixTestTrack("short", durationMs = 3_000L), b)) { now }
        assertEquals(0L, clock.clockMs())
        now = 99_000L
        assertEquals(14_000L, clock.clockMs())
        assertEquals("A 的位置不能超过自身时长", 3_000L, clock.fromPositionMs())
        assertEquals(1f, clock.visualProgress(99_000L))
    }

    @Test fun decodePacingOnlyWaitsWhenAheadOfTheSpeedCap() {
        // 40 倍速：解出 40 秒需要至少 1 秒
        assertEquals(0L, autoMixDecodePauseMs(40_000L, 1_000L, 40.0))
        assertEquals(500L, autoMixDecodePauseMs(40_000L, 500L, 40.0))
        assertEquals(0L, autoMixDecodePauseMs(10_000L, 2_000L, 40.0))
    }
}
