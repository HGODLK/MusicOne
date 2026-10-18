package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlayerInteractionPreparationTest {
    @Test fun pageTransitionsAndOtherScreensNeverAcquireLyricHints() {
        for (phase in MotionPhase.entries.filter { it != MotionPhase.SHOWN }) {
            assertFalse(lyricInteractionHintsAllowed(phase, true, false, true, .5f, .5f, true))
        }
        assertFalse(lyricInteractionHintsAllowed(MotionPhase.SHOWN, false, false, true, .5f, .5f, true))
        assertFalse(lyricInteractionHintsAllowed(MotionPhase.SHOWN, true, true, true, .5f, .5f, true))
        assertTrue(lyricInteractionHintsAllowed(MotionPhase.SHOWN, true, false, false, 0f, 0f, true))
        assertTrue(lyricInteractionHintsAllowed(MotionPhase.SHOWN, true, false, true, .5f, .5f, false))
        assertFalse(lyricInteractionHintsAllowed(MotionPhase.SHOWN, true, false, false, 1f, 1f, true))
    }

    @Test fun overshootAndMidCloseReversalRemainBusyUntilBothWindowsSettle() {
        assertTrue(playerInteractionBusy(MotionPhase.SHOWN, false, 1.01f, 1f))
        assertTrue(playerInteractionBusy(MotionPhase.SHOWN, false, 0f, .01f))
        assertTrue(playerInteractionBusy(MotionPhase.SHOWN, true, 0f, 0f))
        assertTrue(playerInteractionBusy(MotionPhase.DRAGGING, false, 0f, 0f))
        assertTrue(playerInteractionBusy(MotionPhase.MOVING, false, 1f, 1f))
        assertFalse(playerInteractionBusy(MotionPhase.SHOWN, false, 0f, 0f))
        assertFalse(playerInteractionBusy(MotionPhase.SHOWN, false, 1f, 1f))
    }

    @Test fun buttonRequestIsBusyBeforeTheFirstAnimationFrame() {
        assertTrue(playerInteractionBusy(MotionPhase.SHOWN, false, 0f, 0f, requested = true))
        assertTrue(playerInteractionBusy(MotionPhase.SHOWN, false, 1f, 1f, requested = false))
        assertFalse(playerInteractionBusy(MotionPhase.SHOWN, false, 0f, 0f, requested = false))
        assertFalse(playerInteractionBusy(MotionPhase.SHOWN, false, 1f, 1f, requested = true))
    }

    @Test fun preparationNeverExceedsCountOrByteLimitsAndSkipsOversizedRasters() {
        val mib = 1024L * 1024L
        val sizes = listOf(3 * mib, mib, mib / 2, mib / 2, 1L)
        assertEquals(listOf(1, 2, 3), lyricRasterPreparationIndices(sizes))
        assertEquals(listOf(0, 1, 2), lyricRasterPreparationIndices(List(20) { 1L }))
        assertTrue(lyricRasterPreparationIndices(listOf(0L, -1L, Long.MAX_VALUE)).isEmpty())
        assertTrue(lyricRasterPreparationIndices(emptyList()).isEmpty())
    }
}
