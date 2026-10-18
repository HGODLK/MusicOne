package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsOpeningHandoffTest {
    @Test fun firstAndLongIdleReopeningUseIdenticalSpringsAcrossFrameCadences() = runBlocking {
        for (intervals in listOf(listOf(8_333_333L), listOf(16_666_667L), listOf(33_333_333L),
                listOf(16_000_000L, 33_000_000L, 50_000_000L))) {
            val clock = BroadcastFrameClock()
            withContext(clock) {
                var time = 0L
                suspend fun frame(interval: Long) {
                    yield(); time += interval; clock.sendFrame(time)
                    Snapshot.sendApplyNotifications(); yield()
                }
                fun configured(visible: Boolean) = PhoneLyricsMotion(visible).apply {
                    dragTravelPx = 902f; controlsSpacePx = 296f
                    spatialTravel.recordHeight(73f, 6f)
                }
                val first = configured(false)
                val reopened = configured(true)
                val close = launch { reopened.moveTo(false) }
                repeat(200) { frame(16_666_667L) }
                assertTrue(close.isCompleted)
                assertEquals(0f, reopened.lyricsProgress, 0f)
                assertEquals(0f, reopened.headerProgress, 0f)
                frame(6_000_000_000L)
                val firstJob = launch { first.moveTo(true) }
                val reopenJob = launch { reopened.moveTo(true) }
                repeat(180) { index ->
                    frame(intervals[index % intervals.size])
                    assertEquals(first.lyricsProgress, reopened.lyricsProgress, .00001f)
                    assertEquals(first.headerProgress, reopened.headerProgress, .00001f)
                    assertEquals(first.windowOffsetPx, reopened.windowOffsetPx, 0f)
                    assertTrue("封面从起步就连续让位", first.headerProgress + .0001f >= first.lyricsProgress)
                    if (index == 2) assertTrue(first.headerProgress > 0f)
                }
                assertTrue(firstJob.isCompleted && reopenJob.isCompleted)
            }
        }
    }

    @Test fun completeClosingAndImmediateReversalKeepTheSameDragGeometry() {
        for (start in listOf(0f, .0001f, .2f, .7f, .9999f, 1f)) {
            val motion = PhoneLyricsMotion(false)
            runBlocking { motion.lyrics.snapTo(start); motion.header.snapTo(start) }
            motion.beginDrag()
            for (p in listOf(start, .9f, .1f, .00005f, .99995f, start, 1f, 0f)) {
                motion.dragTo(p)
                assertEquals(p, motion.headerProgress, .0001f)
            }
        }
    }

    @Test fun interruptedOpeningTransfersActualPositionAndVelocityToClosing() = runBlocking {
        val clock = BroadcastFrameClock()
        withContext(clock) {
            var time = 0L
            suspend fun frames(count: Int) { repeat(count) {
                yield(); time += 16_666_667; clock.sendFrame(time); Snapshot.sendApplyNotifications(); yield()
            } }
            val motion = PhoneLyricsMotion(false)
            motion.dragTravelPx = 902f; motion.controlsSpacePx = 296f
            motion.spatialTravel.recordHeight(73f, 6f)
            var job = launch { motion.moveTo(true) }
            frames(12)
            val p = motion.lyricsProgress; val h = motion.headerProgress
            val pv = motion.visibleLyricsVelocity; val hv = motion.visibleHeaderVelocity
            assertTrue(p > 0f && h > 0f)
            job.cancelAndJoin()
            job = launch { motion.moveTo(false) }
            yield()
            assertEquals(p, motion.lyricsProgress, .00001f)
            assertEquals(h, motion.headerProgress, .00001f)
            frames(1)
            assertEquals(pv, motion.visibleLyricsVelocity, .001f)
            assertEquals(hv, motion.visibleHeaderVelocity, .001f)
            frames(180)
            assertTrue(job.isCompleted)
            assertEquals(0f, motion.lyricsProgress, 0f)
            assertEquals(0f, motion.headerProgress, 0f)
        }
    }

    @Test fun heldSongLayoutChangesCannotRetargetOpeningGesture() {
        val motion = PhoneLyricsMotion(false)
        motion.dragTravelPx = 902f; motion.controlsSpacePx = 296f
        motion.spatialTravel.recordHeight(73f, 6f)
        motion.beginDrag(); motion.dragTo(.48f)
        val position = motion.windowOffsetPx; val header = motion.headerProgress
        motion.spatialTravel.recordHeight(280f, 6f)
        motion.dragTo(.48f)
        assertEquals(position, motion.windowOffsetPx, 0f)
        assertEquals(header, motion.headerProgress, 0f)
        motion.dragTo(.2f); motion.dragTo(.48f)
        assertEquals(position, motion.windowOffsetPx, 0f)
    }
}
