package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsMotionTest {
    @Test fun reopeningPreparesBeforeMovingButReversalDoesNotRequestAlignment() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield()
                    time += 16_666_667L
                    clock.sendFrame(time)
                    Snapshot.sendApplyNotifications()
                    yield()
                }
            }
            val motion = PhoneLyricsMotion(false)
            var job = launch { motion.moveTo(true, prepareOpening = true) }
            frames(4)
            assertNotNull(motion.openingAlignment)
            assertEquals(0f, motion.lyrics.value, .0001f)
            motion.openingAlignment!!.complete(Unit)
            frames(12)
            assertTrue(motion.lyrics.value > 0f)
            job.cancelAndJoin()
            job = launch { motion.moveTo(false) }
            frames(4)
            job.cancelAndJoin()
            val position = motion.lyrics.value
            assertTrue(position > 0f)
            job = launch { motion.moveTo(true, prepareOpening = true) }
            yield()
            assertNull(motion.openingAlignment)
            assertEquals(position, motion.lyrics.value, .0001f)
            frames(180)
            assertTrue(job.isCompleted)
            assertEquals(1f, motion.lyrics.value, .0001f)
        }
    }

    @Test fun cancelledOpeningCannotReleaseAnotherPreparation() = runBlocking {
        val motion = PhoneLyricsMotion(false)
        var job = launch { motion.moveTo(true, prepareOpening = true) }
        yield()
        val obsolete = motion.openingAlignment!!
        job.cancelAndJoin()
        assertNull(motion.openingAlignment)
        job = launch { motion.moveTo(true, prepareOpening = true) }
        yield()
        val current = motion.openingAlignment!!
        obsolete.complete(Unit)
        yield()
        assertFalse(current.isCompleted)
        assertEquals(0f, motion.lyrics.value, .0001f)
        job.cancelAndJoin()
    }

    @Test fun repeatedReversalsKeepPositionAndReachLatestTarget() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield()
                    time += 16_666_667L
                    clock.sendFrame(time)
                    Snapshot.sendApplyNotifications()
                    yield()
                }
            }
            val motion = PhoneLyricsMotion(false)
            var job = launch { motion.moveTo(true) }
            frames(18)
            repeat(6) { index ->
                val lyricPosition = motion.lyrics.value
                val headerPosition = motion.header.value
                val lyricVelocity = motion.lyrics.velocity
                val headerVelocity = motion.header.velocity
                job.cancelAndJoin()
                job = launch { motion.moveTo(index % 2 != 0) }
                yield()
                assertEquals(lyricPosition, motion.lyrics.value, .0001f)
                assertEquals(headerPosition, motion.header.value, .0001f)
                frames(1)
                assertEquals(lyricVelocity, motion.lyrics.velocity, .001f)
                assertEquals(headerVelocity, motion.header.velocity, .001f)
                frames(5)
            }
            job.cancelAndJoin()
            job = launch { motion.moveTo(false) }
            frames(180)
            assertEquals(0f, motion.lyrics.value, .001f)
            assertEquals(0f, motion.header.value, .001f)
            assertTrue(job.isCompleted)
        }
    }

    @Test fun contactUsesInformationPositionAcrossWindowHeights() {
        assertEquals(.25f, lyricsContactProgress(900f, 100f, 700f), .0001f)
        assertEquals(.5f, lyricsContactProgress(500f, 100f, 300f), .0001f)
    }

    @Test fun openingWaitsForCompleteRowsAndSurvivesReversalWhileWaiting() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield()
                    time += 16_666_667L
                    clock.sendFrame(time)
                    Snapshot.sendApplyNotifications()
                    yield()
                }
            }
            val motion = PhoneLyricsMotion(false)
            val opening = CompletableDeferred<Unit>()
            var job = launch { motion.moveTo(true, opening) }
            frames(4)
            assertEquals(0f, motion.lyrics.value, .0001f)
            opening.complete(Unit)
            frames(12)
            assertTrue(motion.lyrics.value > 0f)
            job.cancelAndJoin()

            val abandonedClose = CompletableDeferred<Unit>()
            job = launch { motion.moveTo(false, abandonedClose) }
            frames(2)
            job.cancelAndJoin()
            val reopening = CompletableDeferred<Unit>()
            job = launch { motion.moveTo(true, reopening) }
            val position = motion.lyrics.value
            abandonedClose.complete(Unit)
            frames(4)
            assertEquals(position, motion.lyrics.value, .0001f)
            reopening.complete(Unit)
            frames(180)
            assertEquals(1f, motion.lyrics.value, .0001f)
            assertEquals(1f, motion.header.value, .0001f)
            assertTrue(job.isCompleted)
        }
    }

    @Test fun interruptedCloseOnlyContinuesAfterItsOwnAlignmentRequest() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) {
                repeat(count) {
                    yield()
                    time += 16_666_667L
                    clock.sendFrame(time)
                    Snapshot.sendApplyNotifications()
                    yield()
                }
            }
            val motion = PhoneLyricsMotion(true)
            val obsolete = CompletableDeferred<Unit>()
            var job = launch { motion.moveTo(false, obsolete) }
            yield()
            job.cancelAndJoin()

            val current = CompletableDeferred<Unit>()
            job = launch { motion.moveTo(false, current) }
            obsolete.complete(Unit)
            frames(2)
            assertEquals(1f, motion.lyrics.value, .0001f)

            current.complete(Unit)
            frames(180)
            assertEquals(0f, motion.lyrics.value, .001f)
            assertEquals(0f, motion.header.value, .001f)
            assertTrue(job.isCompleted)
        }
    }
}
