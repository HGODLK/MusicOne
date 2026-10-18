package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlayerProgressEntranceTest {
    @Test fun openingDelayIncludesTimeAlreadySpentPreparingThePage() {
        val requested = 1_000_000_000L
        assertEquals(278L, playerProgressEntranceDelayMs(requested, requested))
        assertEquals(178L, playerProgressEntranceDelayMs(requested, requested + 100_000_000L))
        assertEquals(1L, playerProgressEntranceDelayMs(requested, requested + 277_999_999L))
        assertEquals(0L, playerProgressEntranceDelayMs(requested, requested + 278_000_000L))
        assertEquals(0L, playerProgressEntranceDelayMs(requested, requested + 900_000_000L))
    }

    @Test fun progressBeginsBeforePageIsShownAndContinuesAcrossPagePhases() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val state = PlayerProgressEntranceState(MotionPhase.HIDDEN, "track", null)
            val requested = System.nanoTime() - 278_000_000L
            state.update(MotionPhase.PREPARING, true, "track", false, null, this, requested)
            yield()
            assertTrue(clock.hasAwaiters)
            clock.sendFrame(1L); yield()
            state.update(MotionPhase.MOVING, true, "track", false, null, this, requested)
            clock.sendFrame(180_000_001L); yield()
            assertTrue(state.value > 0f && state.value < 1f)
            state.update(MotionPhase.SHOWN, true, "track", false, null, this, requested)
            clock.sendFrame(400_000_001L); yield()
            assertEquals(1f, state.value, 0f)
        }
    }

    @Test fun draggingClosingAndReopeningDoNotRestartOrCompleteTheProgressEntrance() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val state = PlayerProgressEntranceState(MotionPhase.HIDDEN, "track", null)
            val requested = System.nanoTime() - 278_000_000L
            state.update(MotionPhase.PREPARING, true, "track", false, null, this, requested)
            yield(); clock.sendFrame(1L); yield()
            clock.sendFrame(90_000_001L); yield()
            val beforeDrag = state.value
            assertTrue(beforeDrag > 0f && beforeDrag < 1f)
            state.update(MotionPhase.DRAGGING, true, "track", false, null, this, requested)
            assertEquals(beforeDrag, state.value, 0f)
            clock.sendFrame(180_000_001L); yield()
            val beforeClosing = state.value
            assertTrue(beforeClosing > beforeDrag && beforeClosing < 1f)
            state.update(MotionPhase.MOVING, false, "track", false, null, this, requested)
            assertEquals(beforeClosing, state.value, 0f)
            state.update(MotionPhase.MOVING, true, "track", false, null, this, System.nanoTime())
            assertEquals(beforeClosing, state.value, 0f)
            clock.sendFrame(400_000_001L); yield()
            assertEquals(1f, state.value, 0f)
        }
    }

    @Test fun hiddenCancelsTheOldJobAndRearmsTheNextOpening() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val state = PlayerProgressEntranceState(MotionPhase.HIDDEN, "track", null)
            val requested = System.nanoTime() - 278_000_000L
            state.update(MotionPhase.PREPARING, true, "track", false, null, this, requested)
            yield(); clock.sendFrame(1L); yield()
            clock.sendFrame(100_000_001L); yield()
            assertTrue(state.value > 0f)
            state.update(MotionPhase.HIDDEN, false, "track", false, null, this, requested)
            assertEquals(0f, state.value, 0f)
            clock.sendFrame(500_000_001L); yield()
            assertEquals(0f, state.value, 0f)
            state.update(MotionPhase.PREPARING, true, "track", false, null, this, requested)
            yield(); clock.sendFrame(600_000_001L); yield()
            assertEquals(0f, state.value, 0f)
            clock.sendFrame(700_000_001L); yield()
            assertTrue(state.value > 0f && state.value < 1f)
            state.cancel()
        }
    }

    @Test fun interruptionDuringTheInitialDelayKeepsTheSamePendingEntrance() = runBlocking {
        val state = PlayerProgressEntranceState(MotionPhase.HIDDEN, "track", null)
        val requested = System.nanoTime()
        state.update(MotionPhase.PREPARING, true, "track", false, null, this, requested)
        yield()
        state.update(MotionPhase.DRAGGING, true, "track", false, null, this, requested)
        assertEquals(0f, state.value, 0f)
        state.update(MotionPhase.MOVING, false, "track", false, null, this, requested)
        assertEquals(0f, state.value, 0f)
        state.update(MotionPhase.MOVING, true, "track", false, null, this, System.nanoTime())
        assertEquals(0f, state.value, 0f)
        state.cancel()
    }

    @Test fun trackChangeLyricSeekAndDirectInteractionStillCancelEntrance() = runBlocking {
        for (reason in 0..3) {
            val state = PlayerProgressEntranceState(MotionPhase.HIDDEN, "track", null)
            state.update(MotionPhase.PREPARING, true, "track", false, null, this, System.nanoTime())
            if (reason == 3) state.cancel() else state.update(MotionPhase.MOVING, true,
                if (reason == 0) "next" else "track", reason == 1,
                if (reason == 2) 1L else null, this, System.nanoTime())
            assertEquals(1f, state.value, 0f)
        }
    }
}
