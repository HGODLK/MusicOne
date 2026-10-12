package com.musicone.demo

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt
import kotlin.math.exp

class PhoneLyricsOpeningHandoffTest {
    @Test fun headerMakesRoomForOriginalSpringAcrossLayouts() {
        for (travel in listOf(480f, 902f, 1600f)) for (compact in listOf(120f, 369f, 460f, 900f))
            for (contact in listOf(0f, .15f, .4f, .59f, .8f, 1f)) {
                val path = PhoneLyricsOpeningHandoff(travel, compact, contact)
                assertEquals(0f, path.header(0f), 0f)
                assertEquals(1f, path.header(1f), 0f)
                var previous = travel
                for (i in 1..1000) {
                    val input = i / 1000f
                    val q = input
                    val offset = phoneLyricsSpatialWindowOffset(travel, q, path.header(q), compact, 6f)
                    assertTrue(offset <= previous)
                    assertEquals(travel * (1f - q), offset, .51f)
                    previous = offset
                }
            }
    }

    @Test fun coarseAndUnevenFramesCannotTurnContactIntoAStoppedPlateau() {
        val path = PhoneLyricsOpeningHandoff(902f, 369f, .5f)
        for (intervals in listOf(listOf(1000.0 / 30), listOf(1000.0 / 60), listOf(1000.0 / 90),
                listOf(1000.0 / 120), listOf(16.0, 33.0, 50.0, 16.0))) {
            var time = 0.0
            var previous = 0f
            repeat(60) { frame ->
                time += intervals[frame % intervals.size] / 1000
                val omega = sqrt(210.0)
                val input = (1 - (1 + omega * time) * exp(-omega * time)).toFloat()
                val offset = phoneLyricsSpatialWindowOffset(902f, input, path.header(input), 369f, 6f)
                val visible = 1f - offset / 902f
                assertEquals(input, visible, .0006f)
                assertTrue(visible >= previous)
                previous = visible
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
            motion.dragTravelPx = 902f; motion.controlsSpacePx = 296f; motion.contactProgress = .5f
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
        motion.dragTravelPx = 902f; motion.controlsSpacePx = 296f; motion.contactProgress = .5f
        motion.spatialTravel.recordHeight(73f, 6f)
        motion.beginDrag(); motion.dragTo(.48f)
        val position = motion.windowOffsetPx; val header = motion.headerProgress
        motion.contactProgress = .8f; motion.spatialTravel.recordHeight(280f, 6f)
        motion.dragTo(.48f)
        assertEquals(position, motion.windowOffsetPx, 0f)
        assertEquals(header, motion.headerProgress, 0f)
        motion.dragTo(.2f); motion.dragTo(.48f)
        assertEquals(position, motion.windowOffsetPx, 0f)
    }
}
