package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricEntranceAlignmentTest {
    @Test fun completeTopRowSlidesIntoTheSameFinalAnchorOnPhoneAndPortraitTablet() {
        // 分别覆盖普通行、带翻译的高行，以及手机和平板的不同顶部留白。
        for ((anchor, rowHeight, clipped) in listOf(Triple(52, 52, 18), Triple(52, 96, 37), Triple(88, 120, 51))) {
            val top = -anchor - clipped
            val reveal = lyricExitScrollDelta(top, rowHeight, -anchor)!!
            val currentOffset = -reveal
            val travel = LyricEntranceTravel(0f, currentOffset)
            assertEquals(-anchor.toFloat(), top - reveal, .001f)
            for (fps in listOf(30, 60, 120)) {
                val offsets = (0..fps).map { travel.remainingOffset(it.toFloat() / fps) }
                assertEquals(currentOffset, offsets.first(), .001f)
                assertTrue(offsets.zipWithNext().all { (a, b) -> b < a })
                assertEquals(0f, offsets.last(), 0f)
                assertEquals(top.toFloat(), top - reveal - currentOffset, .001f)
            }
        }
    }

    @Test fun reopeningFromAnInterruptedCloseKeepsTheActualPositionThenReachesTheAnchor() {
        val actualOffset = 27f
        val resumed = LyricEntranceTravel(.46f, actualOffset)
        assertEquals(actualOffset, resumed.remainingOffset(.46f), 0f)
        assertTrue(resumed.remainingOffset(.47f) < actualOffset)
        assertEquals(0f, resumed.remainingOffset(1f), 0f)
    }

    @Test fun draggingBackAndForwardRetracesTheVisibleEntranceWithoutResettingItsOrigin() {
        val travel = LyricEntranceTravel(.2f, 40f)
        val middle = travel.remainingOffset(.6f)
        assertTrue(travel.remainingOffset(.4f) > middle)
        assertEquals(middle, travel.remainingOffset(.6f), 0f)
        assertEquals(40f, travel.remainingOffset(.1f), 0f)
        assertEquals(0f, travel.remainingOffset(1f), 0f)
    }

    @Test fun playbackCrossingATranslatedLineRetargetsFromItsMeasuredPosition() {
        val original = LyricEntranceTravel(0f, 32f)
        val before = original.remainingOffset(.55f)
        val nextLineActualOffset = before + 96f + 18f
        val next = LyricEntranceTravel(.55f, nextLineActualOffset)
        assertEquals(nextLineActualOffset, next.remainingOffset(.55f), 0f)
        assertTrue(next.remainingOffset(.56f) < nextLineActualOffset)
        assertEquals(0f, next.remainingOffset(1f), 0f)
    }

    @Test fun finalAndReducedMotionFramesLeaveNoResidualForPlaybackToAlign() {
        for (start in listOf(0f, .4f, .99f, 1f)) {
            for (offset in listOf(-80f, 0f, 18f, 132f)) {
                val travel = LyricEntranceTravel(start, offset)
                assertEquals(0f, travel.remainingOffset(1f), 0f)
                assertEquals(0f, travel.remainingOffset(1.01f), 0f)
            }
        }
    }

    @Test fun repeatedFramesAndReversalUseConsumedScrollWithoutAccumulatingExtraMotion() {
        val travel = LyricEntranceTravel(0f, 37f)
        var actualOffset = 37f
        val scroll: (Float) -> Float = { requested ->
            actualOffset -= requested
            requested
        }
        travel.moveTo(.4f, scroll)
        val before = actualOffset
        repeat(10) { travel.moveTo(.4f, scroll) }
        assertEquals(before, actualOffset, 0f)
        travel.moveTo(.2f, scroll)
        assertTrue(actualOffset > before)
        travel.moveTo(.4f, scroll)
        assertEquals(before, actualOffset, .0001f)
        travel.moveTo(1f, scroll)
        assertEquals(0f, actualOffset, .0001f)
        repeat(10) { travel.moveTo(1f, scroll) }
        assertEquals(0f, actualOffset, .0001f)
    }

    @Test fun lateEntranceOwnershipDoesNotReplayThePreparedZeroProgress() {
        for (progress in listOf(.02f, .4f, .65f, .9f)) for (offset in listOf(-178f, 37f, -420f)) {
            val session = LyricEntranceAlignmentSession()
            var actual = offset
            val scroll: (Float) -> Float = { actual -= it; it }
            session.moveTo(8, progress, actual, measured = true, scroll)
            assertEquals(offset, actual, 0f)
            session.moveTo(8, progress + (1f - progress) * .5f, actual, measured = true, scroll)
            assertEquals(offset * .5f, actual, .001f)
            session.moveTo(8, 1f, actual, measured = true, scroll)
            assertEquals(0f, actual, .001f)
        }
    }

    @Test fun restartingOwnershipAtActualProgressKeepsTheUninterruptedPath() {
        var actual = -178f
        val scroll: (Float) -> Float = { actual -= it; it }
        val session = LyricEntranceAlignmentSession()
        session.moveTo(8, .2f, actual, measured = true, scroll)
        session.moveTo(8, .6f, actual, measured = true, scroll)
        val before = actual
        val restarted = LyricEntranceAlignmentSession()
        restarted.moveTo(8, .6f, actual, measured = true, scroll)
        assertEquals(before, actual, 0f)
        restarted.moveTo(8, .8f, actual, measured = true, scroll)
        assertEquals(-44.5f, actual, .001f)
        restarted.moveTo(8, 1f, actual, measured = true, scroll)
        assertEquals(0f, actual, .001f)
    }

    @Test fun nextLineAndEstimatedLayoutHandoffsStartAtTheirActualPositions() {
        val session = LyricEntranceAlignmentSession()
        var actual = -180f
        val scroll: (Float) -> Float = { actual -= it; it }
        session.moveTo(8, .1f, actual, measured = true, scroll)
        session.moveTo(8, .55f, actual, measured = true, scroll)
        actual += 114f
        val nextBefore = actual
        session.moveTo(9, .55f, actual, measured = false, scroll)
        assertEquals(nextBefore, actual, 0f)
        // 从邻近行估计转为实测时，保留当前屏幕位置，不补跳估计差值。
        actual += 24f
        val measuredBefore = actual
        session.moveTo(9, .6f, actual, measured = true, scroll)
        assertEquals(measuredBefore, actual, 0f)
        session.moveTo(9, 1f, actual, measured = true, scroll)
        assertEquals(0f, actual, .001f)
    }

    @Test fun handoffKeepsRepeatedFramesReversalAndReducedMotionEndpoints() {
        var actual = -178f
        val scroll: (Float) -> Float = { actual -= it; it }
        val session = LyricEntranceAlignmentSession()
        session.moveTo(8, .02f, actual, measured = true, scroll)
        session.moveTo(8, .6f, actual, measured = true, scroll)
        val middle = actual
        repeat(8) { session.moveTo(8, .6f, actual, measured = true, scroll) }
        assertEquals(middle, actual, 0f)
        session.moveTo(8, .4f, actual, measured = true, scroll)
        assertTrue(actual < middle)
        session.moveTo(8, .6f, actual, measured = true, scroll)
        assertEquals(middle, actual, .001f)
        session.moveTo(8, 1f, actual, measured = true, scroll)
        assertEquals(0f, actual, .001f)
        val instant = LyricEntranceAlignmentSession()
        var finalScroll = 0f
        instant.moveTo(8, 1f, -178f, measured = true) { finalScroll += it; it }
        assertEquals(-178f, finalScroll, 0f)
    }
}
