package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsGestureTest {
    @Test fun reversingDragBeforeReleaseKeepsOriginalState() {
        assertFalse(lyricsDragTarget(.03f, -1500f))
        assertTrue(lyricsDragTarget(.97f, 1500f))
        assertFalse(lyricsDragTarget(.4f, 0f))
        assertTrue(lyricsDragTarget(.6f, 0f))
        assertTrue(lyricsDragTarget(.4f, -1200f))
        assertFalse(lyricsDragTarget(.6f, 1200f))
    }

    @Test fun openingAndClosingDragFollowSameGeometry() {
        repeat(101) {
            val p = it / 100f
            assertEquals(lyricsDragHeaderProgress(p, 0f, 0f, .4f),
                lyricsDragHeaderProgress(p, 1f, 1f, .4f), .0001f)
        }
        assertEquals(.25f, lyricsDragHeaderProgress(.6f, .6f, .25f, .4f), .0001f)
    }

    @Test fun draggedPositionIsRetainedUntilAlignmentAndSettlesAfterReversal() = runBlocking {
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
            motion.finishDrag()
            val aligned = CompletableDeferred<Unit>()
            val job = launch { motion.moveTo(false, aligned) }
            frames(4)
            assertEquals(.45f, motion.lyricsProgress, .0001f)
            aligned.complete(Unit)
            frames(180)
            assertEquals(0f, motion.lyricsProgress, .0001f)
            assertEquals(0f, motion.headerProgress, .0001f)
            assertTrue(job.isCompleted)
        }
    }
}
