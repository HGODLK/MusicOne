package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistPullGestureTest {
    @Test fun phonePullRequiresTheListToBeAtTheTop() {
        assertFalse(playlistPullStartEligible(false, false, MotionPhase.SHOWN))
        assertTrue(playlistPullStartEligible(true, false, MotionPhase.SHOWN))
    }

    @Test fun tabletCoverPullDoesNotRequireTheTrackListToBeAtTheTop() {
        assertTrue(playlistPullStartEligible(false, false, MotionPhase.SHOWN, requireScrollAtTop = false))
        assertFalse(playlistPullStartEligible(false, true, MotionPhase.SHOWN, requireScrollAtTop = false))
    }

    @Test fun offscreenCoverRemainsMountedAndReturnsContinuouslyAfterRelease() = checkOffscreenReturn(false)
    @Test fun cancelledOffscreenPullReboundsContinuouslyToOriginalCover() = checkOffscreenReturn(true)

    @Test fun pullingDownThenAboveTheScreenKeepsTheFlyingCoverUntilItReturns() = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280)
        val source = Rect(20f, 60f, 100f, 140f)
        val target = Rect(50f, 120f, 350f, 420f)
        motion.updateHost(Rect(0f, 0f, 400f, 800f))
        motion.sources["cover"] = MotionAnchor(source)
        motion.targets["cover"] = MotionAnchor(target)
        assertTrue(motion.beginDrag())
        val pull = PlaylistPullState().apply { begin(); displacement = Offset(15f, 200f) }
        motion.dragTo(playlistPullProgress(200f, 520f))
        pull.displacement = Offset(15f, -1100f)
        motion.dragTo(playlistPullProgress(pull.displacement.y, 520f))
        assertEquals(1f, motion.value, 0f)
        val dragged = pull.artworkBounds(motion, source, target)
        assertTrue(dragged.bottom < 0f)
        pull.releaseProgress = motion.value
        pull.released = true
        pull.returnToOrigin(motion)
        yield()
        assertEquals(dragged, pull.artworkBounds(motion, source, target))
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) { yield(); time += 16_666_667; clock.sendFrame(time); yield() }
        }
        frames(5)
        val middle = pull.artworkBounds(motion, source, target)
        assertTrue(motion.moving)
        assertTrue(middle.center.y > dragged.center.y)
        assertTrue(middle.center.y < target.center.y)
        frames(100)
        assertEquals(MotionPhase.SHOWN, motion.phase)
        assertEquals(target, pull.artworkBounds(motion, source, target))
        coroutineContext.cancelChildren()
    }

    private fun checkOffscreenReturn(reopen: Boolean) = runBlocking {
        val clock = BroadcastFrameClock()
        val motion = PageMotion(CoroutineScope(coroutineContext + clock), true, 320, 280)
        val source = Rect(20f, 60f, 100f, 140f)
        val target = Rect(50f, 120f, 350f, 420f)
        motion.updateHost(Rect(0f, 0f, 400f, 800f))
        motion.sources["cover"] = MotionAnchor(source)
        motion.targets["cover"] = MotionAnchor(target)
        assertTrue(motion.beginDrag())
        val pull = PlaylistPullState().apply { active = true; displacement = Offset(15f, 1100f) }
        motion.dragTo(playlistPullProgress(pull.displacement.y, 520f))
        val dragged = pull.artworkBounds(motion, source, target)
        assertTrue(dragged.top > 800f)
        pull.releaseProgress = motion.value
        pull.released = true
        assertTrue(pull.releaseProgress > 0f)
        if (reopen) pull.returnToOrigin(motion) else motion.request(false)
        yield()
        assertEquals(dragged, pull.artworkBounds(motion, source, target))
        assertTrue(motion.hasSharedCover)
        var time = 0L
        repeat(5) { yield(); time += 16_666_667; clock.sendFrame(time); yield() }
        val middle = pull.artworkBounds(motion, source, target)
        val destination = if (reopen) target else source
        assertTrue(motion.mounted)
        assertTrue(middle.center.y < dragged.center.y)
        assertTrue(middle.center.y > destination.center.y)
        repeat(100) { yield(); time += 16_666_667; clock.sendFrame(time); yield() }
        assertEquals(if (reopen) MotionPhase.SHOWN else MotionPhase.HIDDEN, motion.phase)
        assertEquals(destination, pull.artworkBounds(motion, source, target))
        coroutineContext.cancelChildren()
    }
}
