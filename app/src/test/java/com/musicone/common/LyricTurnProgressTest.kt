package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LyricTurnProgressTest {
    @Test fun turnHoldsTheLastDisplayedTimeAndCatchesUpWithoutAReleaseJump() {
        val clock = LyricTurnProgress()
        assertEquals(10_000L, clock.sample(10_000, 0, false, 1))
        assertEquals(10_000L, clock.sample(10_016, 16_000_000, true, 1))
        assertEquals(10_000L, clock.sample(11_016, 1_016_000_000, true, 1))
        assertEquals(10_000L, clock.sample(11_032, 1_032_000_000, false, 1))
        assertTrue(clock.catchingUp)
        val positions = (1..20).map { frame ->
            clock.sample(11_032L + frame * 16, 1_032_000_000L + frame * 16_000_000L, false, 1)
        }
        assertTrue(positions.first() > 10_000 && positions.first() < 11_048)
        assertTrue(positions.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(11_352L, positions.last())
        assertFalse(clock.presenting)
    }

    @Test fun anotherTurnDuringCatchUpFreezesTheActuallyPresentedPosition() {
        val clock = LyricTurnProgress()
        clock.sample(1000, 0, false, 1)
        clock.sample(1100, 100_000_000, true, 1)
        clock.sample(2000, 1_000_000_000, false, 1)
        val midway = clock.sample(2080, 1_080_000_000, false, 1)
        assertEquals(midway, clock.sample(2096, 1_096_000_000, true, 1))
        assertEquals(midway, clock.sample(3000, 2_000_000_000, true, 1))
        assertEquals(midway, clock.sample(3016, 2_016_000_000, false, 1))
        assertEquals(3336L, clock.sample(3336, 2_336_000_000, false, 1))
    }

    @Test fun seeksAndNewPlaybackRoundsDiscardThePreviousTurnLag() {
        val clock = LyricTurnProgress()
        clock.sample(10_000, 0, false, 1)
        clock.sample(11_000, 1_000_000_000, true, 1)
        assertEquals(60_000L, clock.sample(60_000, 1_016_000_000, true, 2))
        assertEquals(2000L, clock.sample(2000, 1_032_000_000, false, 3, seeking = true))
        assertFalse(clock.presenting)
        assertEquals(2016L, clock.sample(2016, 1_048_000_000, false, 3))
    }

    @Test fun pausedPlaybackAlsoCatchesUpAndReducedMotionUsesItsNormalEndpoint() {
        val clock = LyricTurnProgress()
        clock.sample(1000, 0, false, 1)
        clock.sample(1500, 500_000_000, true, 1)
        assertEquals(1000L, clock.sample(1500, 516_000_000, false, 1))
        val halfway = clock.sample(1500, 616_000_000, false, 1)
        assertTrue(halfway in 1001..1499)
        assertEquals(1500L, clock.sample(1500, 836_000_000, false, 1))
        clock.sample(1600, 936_000_000, true, 1)
        assertEquals(2000L, clock.sample(2000, 1_336_000_000, false, 1, reduceMotion = true))
    }
}
