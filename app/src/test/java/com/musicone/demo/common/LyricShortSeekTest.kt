package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricShortSeekTest {
    @Test fun clickOwnsScrollBeforePlaybackCallbackAndReleasesAfterCompletion() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val seek = LyricShortSeek()
            val finish = CompletableDeferred<Unit>()
            var ready = 0
            val job = seek.launchScroll(this, { finish.await() }) { ready++ }
            assertTrue(seek.active)
            yield()
            clock.sendFrame(16_666_667L)
            yield()
            assertEquals(1, ready)
            assertTrue(seek.active)
            finish.complete(Unit)
            job.join()
            assertFalse(seek.active)
        }
    }

    @Test fun supersededClickCannotReleaseLatestScrollOwnership() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            val seek = LyricShortSeek()
            val first = seek.launchScroll(this, { awaitCancellation() })
            yield()
            val finish = CompletableDeferred<Unit>()
            val latest = seek.launchScroll(this, { finish.await() })
            first.join()
            assertTrue(seek.active)
            yield()
            clock.sendFrame(16_666_667L)
            finish.complete(Unit)
            latest.join()
            assertFalse(seek.active)
        }
    }

    @Test fun cancelledPreparationReleasesWaitingProgressSeek() = runBlocking {
        withContext(BroadcastFrameClock()) {
            val seek = LyricShortSeek()
            var ready = 0
            val job = seek.launchScroll(this, { awaitCancellation() }) { ready++ }
            yield()
            seek.cancel()
            job.join()
            assertEquals(1, ready)
            assertFalse(seek.active)
        }
    }

    @Test fun completingShortSeekAcknowledgesTargetWithoutAnotherFollowScroll() {
        val step = LyricPlaybackStepMotion(2)
        step.consume(4, enabled = true)
        assertEquals(LyricPlaybackStepChange.NONE, step.consume(4, enabled = true))
        assertEquals(LyricPlaybackStepChange.ANIMATE, step.consume(5, enabled = true))
        assertEquals(220f, lyricPlaybackMotionStiffness(240f, LyricPlaybackMotionPurpose.SEEK), 0f)
    }

    @Test fun cancellingBeforeCoroutineStartsStillReleasesTheRequest() = runBlocking {
        val seek = LyricShortSeek()
        var ready = 0
        val job = seek.launchScroll(this, { error("已取消的任务不应开始滚动") }) { ready++ }
        seek.cancel()
        job.join()
        assertEquals(1, ready)
        assertFalse(seek.active)
    }
}
