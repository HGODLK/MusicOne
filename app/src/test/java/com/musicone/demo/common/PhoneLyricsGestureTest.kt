package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsGestureTest {
    @Test fun releaseStartsImmediatelyAndTheStateNotificationDoesNotRestartIt() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val motion = PhoneLyricsMotion(false, this)
            motion.dragTravelPx = 600f
            motion.beginDrag()
            motion.dragTo(.6f)
            motion.settleDrag(true, -900f, releaseTimeNanos = 0L)
            assertTrue(motion.settlingGesture(true))
            assertEquals(.6f, motion.lyricsProgress, .001f)
            yield()
            clock.sendFrame(16_666_667L)
            yield()
            assertTrue(motion.lyricsProgress > .6f)
            assertTrue(motion.settlingGesture(true))
            motion.beginDrag()
            assertFalse(motion.settlingGesture(true))
            coroutineContext.cancelChildren()
        }
    }

    @Test fun lyricsEndpointWinsOverTheOppositeVelocityFromLiftHook() {
        assertTrue(lyricsDragTarget(.96f, 900f, -400f))
        assertFalse(lyricsDragTarget(.04f, -900f, 400f, true))
    }

    @Test fun headerBeginsMakingRoomWithTheFirstGestureDisplacement() {
        val motion = PhoneLyricsMotion(false)
        motion.beginDrag()
        motion.dragTo(.01f)
        assertTrue("手势起步不能等歌词接触封面后才让位", motion.headerProgress > 0f)
        val firstHeader = motion.headerProgress
        motion.dragTo(.02f)
        assertTrue(motion.headerProgress > firstHeader)
        motion.dragTo(0f)
        assertEquals(0f, motion.headerProgress, 0f)
    }

    @Test fun reversingDragBeforeReleaseKeepsOriginalState() {
        assertTrue(lyricsDragTarget(.03f, -1500f, -24f))
        assertFalse(lyricsDragTarget(.03f, -1500f, -4f))
        assertFalse(lyricsDragTarget(.97f, 1500f, 24f, true))
        assertFalse(lyricsDragTarget(.4f, 0f))
        assertTrue(lyricsDragTarget(.6f, 0f))
        assertTrue(lyricsDragTarget(.4f, -1200f))
        assertTrue(lyricsDragTarget(.6f, 1200f))
        assertFalse(lyricsDragTarget(.4f, 0f, -100f, reverseDistanceDp = 32f))
    }

    @Test fun openingAndClosingDragFollowSameGeometry() {
        repeat(101) {
            val p = it / 100f
            assertEquals(lyricsDragHeaderProgress(p, 0f, 0f),
                lyricsDragHeaderProgress(p, 1f, 1f), .0001f)
        }
        assertEquals(.25f, lyricsDragHeaderProgress(.6f, .6f, .25f), .0001f)
    }

    @Test fun draggedPositionSettlesImmediatelyWithoutWaitingForObsoleteAlignment() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield(); time += 16_666_667L; clock.sendFrame(time)
                    Snapshot.sendApplyNotifications(); yield()
                }
            }
            val motion = PhoneLyricsMotion(true)
            motion.beginDrag()
            motion.dragTo(.45f)
            val header = motion.headerProgress
            motion.dragTo(.9f)
            motion.dragTo(.45f)
            assertEquals(header, motion.headerProgress, .0001f)
            motion.finishDrag(releaseTimeNanos = time)
            val aligned = CompletableDeferred<Unit>()
            val job = launch { motion.moveTo(false, aligned) }
            assertEquals(.45f, motion.lyricsProgress, .0001f)
            frames(2)
            assertTrue(motion.lyricsProgress < .45f)
            assertTrue(motion.headerProgress < header)
            aligned.complete(Unit)
            frames(180)
            assertEquals(0f, motion.lyricsProgress, .0001f)
            assertEquals(0f, motion.headerProgress, .0001f)
            assertTrue(job.isCompleted)
        }
    }
}
