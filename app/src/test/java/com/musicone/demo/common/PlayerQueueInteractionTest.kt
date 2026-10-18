package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerQueueInteractionTest {
    @Test fun capsuleOpeningTracksPixelsBeforeReleaseOnPhoneAndTablet() = runBlocking {
        val clock = BroadcastFrameClock()
        for (height in listOf(480, 1360)) {
            val queue = PlayerQueueState(CoroutineScope(coroutineContext + clock))
            assertFalse(queue.beginOpeningDrag())
            queue.updateHeight(height)
            assertTrue(queue.beginOpeningDrag())
            queue.dragOpeningBy(-96f)
            yield()
            assertTrue(queue.visible)
            assertEquals(MotionPhase.DRAGGING, queue.motion.phase)
            assertEquals(96f, queue.motion.value * height, .001f)
            queue.dragOpeningBy(32f)
            assertEquals(64f, queue.motion.value * height, .001f)
            queue.dragOpeningBy(100f)
            assertEquals(0f, queue.motion.value, 0f)
            queue.dragOpeningBy(-height * 2f)
            assertEquals(1f, queue.motion.value, 0f)
            coroutineContext.cancelChildren()
        }
    }

    @Test fun capsuleReleaseContinuesFromFingerAndSettlesToChosenTarget() = runBlocking {
        val clock = BroadcastFrameClock()
        var time = 0L
        for (open in listOf(true, false)) {
            val queue = PlayerQueueState(CoroutineScope(coroutineContext + clock))
            queue.updateHeight(600)
            assertTrue(queue.beginOpeningDrag())
            queue.dragOpeningBy(-120f)
            yield()
            queue.finishOpeningDrag(open, if (open) -600f else 0f, releaseTimeNanos = time)
            assertEquals(.2f, queue.motion.value, .0001f)
            val expectedPhase = if (open) MotionPhase.SHOWN else MotionPhase.HIDDEN
            var frames = 0
            while (queue.motion.phase != expectedPhase && frames < 600) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                frames++
            }
            yield()
            assertEquals(if (open) MotionPhase.SHOWN else MotionPhase.HIDDEN, queue.motion.phase)
            assertEquals(if (open) 1f else 0f, queue.motion.value, 0f)
            coroutineContext.cancelChildren()
        }
    }

    @Test fun cancelledCapsuleOpeningDuringPreparationNeverAppearsLater() = runBlocking {
        val clock = BroadcastFrameClock()
        val queue = PlayerQueueState(CoroutineScope(coroutineContext + clock))
        queue.updateHeight(600)
        assertTrue(queue.beginOpeningDrag())
        queue.dragOpeningBy(-24f)
        queue.finishOpeningDrag(false, 0f)
        repeat(10) { yield(); clock.sendFrame(it * 16_666_667L) }
        assertFalse(queue.visible)
        assertFalse(queue.motion.wantsOpen)
        coroutineContext.cancelChildren()
    }

    @Test fun listCanDismissOnlyFromRestingTopAndNeverFromRebound() {
        assertTrue(queueListCanStartDismiss(true, false, false, MotionPhase.SHOWN))
        assertFalse(queueListCanStartDismiss(false, false, false, MotionPhase.SHOWN))
        assertFalse(queueListCanStartDismiss(true, true, false, MotionPhase.SHOWN))
        assertFalse(queueListCanStartDismiss(true, false, true, MotionPhase.SHOWN))
        for (phase in MotionPhase.entries.filterNot { it == MotionPhase.SHOWN }) {
            assertFalse(queueListCanStartDismiss(true, false, false, phase))
        }
    }

    @Test fun tinyMovementAndCancellationCannotCloseButDeliberatePullCan() {
        assertTrue(queueGestureStaysOpen(.99f, 2f, 1200f, 0f, true))
        assertTrue(queueGestureStaysOpen(.95f, 20f, 0f, 0f, true))
        assertTrue(queueGestureStaysOpen(.5f, 200f, 1000f, 0f, false))
        assertFalse(queueGestureStaysOpen(.85f, 70f, 0f, 0f, true))
        assertFalse(queueGestureStaysOpen(.94f, 30f, 900f, 0f, true))
        assertTrue(queueGestureStaysOpen(.96f, 40f, 900f, 30f, true))
    }

    @Test fun capsuleDirectionAndReleaseKeepNavigationSeparateFromQueueOpening() {
        assertEquals(CapsuleGestureDirection.PENDING, capsuleGestureDirection(2f, -3f, 8f))
        assertEquals(CapsuleGestureDirection.UP, capsuleGestureDirection(3f, -20f, 8f))
        assertEquals(CapsuleGestureDirection.HORIZONTAL, capsuleGestureDirection(30f, -3f, 8f))
        assertEquals(CapsuleGestureDirection.IGNORED, capsuleGestureDirection(2f, 20f, 8f))
        assertTrue(capsuleShouldOpenQueue(40f, 0f, 0f, true))
        assertTrue(capsuleShouldOpenQueue(20f, -900f, 0f, true))
        assertFalse(capsuleShouldOpenQueue(5f, -2000f, 0f, true))
        assertFalse(capsuleShouldOpenQueue(50f, -900f, 0f, false))
        assertFalse(capsuleShouldOpenQueue(40f, -900f, 30f, true))
    }

    @Test fun sheetAlwaysFitsShortLandscapeWindows() {
        for (height in listOf(0f, 180f, 280f, 480f, 900f, 1400f)) {
            val sheet = playerQueueSheetHeight(height)
            assertTrue(sheet >= 0f && sheet <= height)
        }
        assertEquals(280f, playerQueueSheetHeight(280f), .01f)
        assertEquals(680f, playerQueueSheetHeight(1400f), .01f)
    }
}
