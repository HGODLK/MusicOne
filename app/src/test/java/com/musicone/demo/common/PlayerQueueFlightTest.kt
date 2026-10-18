package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlayerQueueFlightTest {
    @Test fun movingTheTargetEveryFrameDoesNotFreezeTheFlight() {
        val flight = PlayerQueueFlight(240f)
        repeat(20) { frame ->
            val previous = flight.position
            flight.advance(-132f - frame * 2f, 1f / 120f)
            assertTrue("第 $frame 帧仍须推进", flight.position < previous)
        }
    }

    @Test fun theUserCanFollowTheFlightWithScrollUntilTheWholeRowLeaves() {
        val flight = PlayerQueueFlight(240f)
        var follow = 0f
        repeat(40) {
            flight.advance(-132f - follow, 1f / 120f)
            follow = 240f - flight.position
            assertFalse(queueRowOutsideViewport(flight.position + follow, 66, 680))
        }
        var frames = 0
        while (!queueRowOutsideViewport(flight.position + follow, 66, 680) && frames++ < 240) {
            flight.advance(-132f - follow, 1f / 120f)
        }
        assertTrue(frames < 240)
        assertTrue(flight.position + follow > -132f)
    }

    @Test fun bottomBoundaryAndReducedMotionCompleteWithoutAnOffscreenTail() {
        assertFalse(queueRowOutsideViewport(679f, 66, 680))
        assertTrue(queueRowOutsideViewport(680f, 66, 680))
        val flight = PlayerQueueFlight(120f)
        flight.advance(746f, 0f, immediate = true)
        assertEquals(746f, flight.position, 0f)
    }
}
