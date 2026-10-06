package com.musicone.demo

import androidx.compose.animation.core.tween
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PageMotionTest {
    @Test fun playerReversalsPreserveVelocityAndCompleteOnlyLatestRequest() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280,
            PlayerReturnAnimation, PlayerEnterAnimation)
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                yield()
            }
        }
        var hidden = 0
        motion.onHidden = { hidden++ }
        motion.request(false)
        frames(8)
        repeat(6) { index ->
            val position = motion.value
            val velocity = motion.progress.velocity
            motion.request(index % 2 == 0)
            assertEquals(position, motion.value, .0001f)
            frames(1)
            assertEquals(velocity, motion.progress.velocity, .001f)
            frames(5)
        }
        motion.request(true)
        frames(100)
        assertEquals(MotionPhase.SHOWN, motion.phase)
        assertEquals(0, hidden)
        motion.request(false)
        frames(100)
        assertEquals(MotionPhase.HIDDEN, motion.phase)
        assertEquals(1, hidden)
        coroutineContext.cancelChildren()
    }

    @Test fun closingKeepsPageMountedUntilAnimationFinishes() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280)
        var hidden = 0
        motion.onHidden = { hidden++ }
        motion.request(false)
        assertTrue(motion.mounted)
        assertTrue(motion.targetContentHandoff)
        repeat(30) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertFalse(motion.mounted)
        assertFalse(motion.targetContentHandoff)
        assertEquals(1, hidden)
        coroutineContext.cancelChildren()
    }

    @Test fun reversingExitContinuesFromCurrentProgress() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280)
        var hidden = false
        motion.onHidden = { hidden = true }
        motion.request(false)
        repeat(8) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        val middle = motion.value
        assertTrue(middle > 0f && middle < 1f)
        motion.request(true)
        assertEquals(middle, motion.value)
        repeat(35) { yield(); clock.sendFrame((it + 8) * 16_000_000L) }
        yield()
        assertEquals(MotionPhase.SHOWN, motion.phase)
        assertFalse(hidden)
        coroutineContext.cancelChildren()
    }

    @Test fun closingDuringPreparationCannotReopenFromLateTarget() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), false, 320, 280)
        motion.updateHost(Rect(0f, 0f, 1000f, 800f))
        motion.request(true)
        yield()
        assertEquals(MotionPhase.PREPARING, motion.phase)
        motion.request(false)
        motion.targets["cover"] = MotionAnchor(Rect(0f, 0f, 300f, 300f))
        repeat(10) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertEquals(MotionPhase.HIDDEN, motion.phase)
        coroutineContext.cancelChildren()
    }

    @Test fun duplicateOpenRequestKeepsCurrentPreparation() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), false, 320, 280)
        motion.updateHost(Rect(0f, 0f, 1000f, 800f))
        motion.request(true)
        val preparingJob = motion.phase
        motion.request(true)

        assertEquals(MotionPhase.PREPARING, preparingJob)
        assertEquals(MotionPhase.PREPARING, motion.phase)
        coroutineContext.cancelChildren()
    }

    @Test fun cancelledDragReturnsToOpenState() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 420, 340)
        assertTrue(motion.beginDrag())
        assertTrue(motion.targetContentHandoff)
        motion.dragTo(.65f)
        assertEquals(.65f, motion.value)
        motion.request(true, rebound = true)
        repeat(100) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertEquals(MotionPhase.SHOWN, motion.phase)
        assertEquals(1f, motion.value)
        assertFalse(motion.targetContentHandoff)
        coroutineContext.cancelChildren()
    }

    @Test fun playlistReturnKeepsDragVelocityAndNonlinearFinish() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280, PlaylistReturnAnimation)
        assertTrue(motion.beginDrag())
        motion.dragTo(.3f)
        motion.releaseDragWithVelocity(-.4f)
        motion.request(false)
        repeat(8) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertTrue(motion.mounted)
        assertTrue(motion.value > 0f)
        repeat(100) { yield(); clock.sendFrame((it + 8) * 16_000_000L) }
        yield()
        assertFalse(motion.mounted)
        coroutineContext.cancelChildren()
    }

    @Test fun customExitAnimationAlsoAppliesWithoutDrag() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280, tween(1000))
        motion.request(false)
        repeat(30) { yield(); clock.sendFrame(it * 16_000_000L) }
        yield()
        assertTrue(motion.mounted)
        repeat(50) { yield(); clock.sendFrame((it + 30) * 16_000_000L) }
        yield()
        assertFalse(motion.mounted)
        coroutineContext.cancelChildren()
    }

    @Test fun playerControlHandoffFreezesIconUntilTransitionCompletes() {
        val handoff = PlayerControlHandoff()
        val adaptiveInk = androidx.compose.ui.graphics.Color.White
        handoff.observeInk("favorite", adaptiveInk)
        handoff.observeInk("next", adaptiveInk)
        handoff.begin(true)
        handoff.observeInk("favorite", androidx.compose.ui.graphics.Color.Black)
        handoff.observeInk("next", androidx.compose.ui.graphics.Color.Black)
        assertTrue(handoff.displayedPlaying(false))
        assertEquals(adaptiveInk, handoff.sourceInk("favorite", androidx.compose.ui.graphics.Color.Black))
        assertEquals(adaptiveInk, handoff.sourceInk("next", androidx.compose.ui.graphics.Color.Black))
        handoff.complete()
        assertFalse(handoff.displayedPlaying(false))
    }
}
