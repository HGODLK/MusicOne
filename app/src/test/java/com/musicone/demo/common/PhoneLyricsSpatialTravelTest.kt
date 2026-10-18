package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PhoneLyricsSpatialTravelTest {
    @Test fun entranceAlignmentKeepsCombinedScreenTravelMonotonicAcrossLayouts() {
        for (travel in listOf(480f, 922f, 1600f)) {
            for (origin in listOf(-220f, -292f, 73f)) {
                val alignment = LyricEntranceTravel(0f, origin)
                val positions = (0..100).map { step ->
                    val p = step / 100f
                    val offset = phoneLyricsSpatialWindowOffset(travel, p,
                        lyricsDragHeaderProgress(p, 0f, 0f), 418f, 6f)
                    offset + alignment.remainingOffset(phoneLyricsEntranceProgress(offset, travel))
                }
                assertTrue("连续让位期间合成位置不能向下回退", positions.zipWithNext().all { (a, b) -> b <= a + .001f })
                assertEquals(0f, positions.last(), .001f)
            }
        }
    }

    @Test fun shortParagraphsKeepTwoAndWrappedParagraphsKeepOneCompleteBlock() {
        assertEquals(122f, phoneLyricsCompactHeight(52f, 52f, 18f, 52f, 52f, 1f), 0f)
        assertEquals(164f, phoneLyricsCompactHeight(73f, 73f, 18f, 73f, 73f, 1f), 0f)
        assertEquals(115f, phoneLyricsCompactHeight(115f, 73f, 18f, 73f, 73f, 1f), 0f)
        assertEquals(52f, phoneLyricsCompactHeight(52f, 94f, 18f, 52f, 52f, 1f), 0f)
        assertEquals(260f, phoneLyricsCompactHeight(260f, null, 18f, 73f, null, 1f), 0f)
    }

    @Test fun intermediateLayoutUpdatesCannotChangeTheCapturedPath() {
        val travel = PhoneLyricsSpatialTravel()
        travel.recordHeight(164f, 6f)
        travel.capture()
        val held = phoneLyricsSpatialWindowOffset(940f, .65f, .2f, travel.compactHeightPx, travel.softnessPx)
        travel.recordHeight(280f, 12f)
        assertEquals(held, phoneLyricsSpatialWindowOffset(940f, .65f, .2f,
            travel.compactHeightPx, travel.softnessPx), 0f)
        travel.capture()
        assertEquals(280f, travel.compactHeightPx, 0f)
    }

    @Test fun compactHeaderRetainsMeasuredParagraphHeightInsteadOfSeveralScreenfuls() {
        for (distance in listOf(480f, 940f, 1600f)) {
            for (paragraphs in listOf(52f, 115f, 164f, 280f)) {
                val originalExposed = distance * .85f
                if (originalExposed <= paragraphs) continue
                val offset = phoneLyricsSpatialWindowOffset(distance, .85f, 0f, paragraphs, 6f)
                val exposed = distance - offset
                assertTrue(exposed >= paragraphs - 1f)
                assertTrue(exposed <= paragraphs + 7f)
                assertTrue(exposed < originalExposed)
            }
        }
    }

    @Test fun closedAndExpandedEndpointsKeepOriginalGeometryWithDrawingBuffers() {
        for ((height, header, buffer) in listOf(Triple(600, 86, 154), Triple(900, 100, 140), Triple(1280, 110, 180))) {
            val distance = phoneLyricsWindowTravel(height, header, buffer, buffer)
            for (paragraphs in listOf(52f, 115f, 280f)) {
                val closed = phoneLyricsSpatialWindowOffset(distance, 0f, .3f, paragraphs, 6f)
                val expanded = phoneLyricsSpatialWindowOffset(distance, 1f, 1f, paragraphs, 6f)
                assertEquals(height + buffer, phoneLyricsWindowTop(height, header, buffer, 0f,
                    false, buffer, closed))
                assertEquals(header - buffer, phoneLyricsWindowTop(height, header, buffer, 1f,
                    false, buffer, expanded))
                assertEquals(header - buffer, phoneLyricsWindowTop(height, header, buffer, 0f,
                    true, buffer, closed))
            }
        }
    }

    @Test fun lyricsEnterFromTheFirstMovementAndReversalRetracesTheSamePosition() {
        val positions = (0..100).map { step ->
            val p = step / 100f
            phoneLyricsSpatialWindowOffset(940f, p, lyricsDragHeaderProgress(p, 0f, 0f), 164f, 6f)
        }
        assertTrue(positions[1] < positions[0])
        assertTrue(positions.zipWithNext().all { (before, after) -> after <= before })
        assertEquals(0f, positions.last(), 0f)
        val forward = phoneLyricsSpatialWindowOffset(940f, .65f, .3f, 164f, 6f)
        phoneLyricsSpatialWindowOffset(940f, .4f, .1f, 164f, 6f)
        assertEquals(forward, phoneLyricsSpatialWindowOffset(940f, .65f, .3f, 164f, 6f), 0f)
    }

    @Test fun retainedParagraphsReachAboveTheGlassInsteadOfStayingUnderTheControls() {
        val motion = PhoneLyricsMotion(false)
        motion.dragTravelPx = 940f
        motion.controlsSpacePx = 296f
        motion.spatialTravel.recordHeight(164f, 6f)
        motion.beginDrag()
        motion.dragTo(.85f)
        val clearExposed = motion.dragTravelPx - motion.windowOffsetPx - motion.controlsSpacePx
        assertTrue(clearExposed >= 164f)
        assertTrue(clearExposed <= motion.dragTravelPx - motion.controlsSpacePx)
        assertTrue(motion.headerProgress > 0f)
        motion.dragTo(.02f)
        assertEquals(921f, motion.windowOffsetPx, 0f)
    }

    @Test fun actualSpatialOffsetKeepsGlassAtTheControlsForEveryIntermediatePosition() {
        for ((height, header, buffer) in listOf(Triple(600, 86, 154), Triple(900, 100, 140), Triple(1280, 110, 180))) {
            val distance = phoneLyricsWindowTravel(height, header, buffer, buffer)
            for (step in 0..100) for (hidden in listOf(0f, .5f, 1f)) {
                val p = step / 100f
                val offset = phoneLyricsSpatialWindowOffset(distance, p,
                    lyricsDragHeaderProgress(p, 0f, 0f), 164f, 6f)
                val windowTop = phoneLyricsWindowTop(height, header, buffer, p, false, buffer, offset)
                for (navigation in listOf(0, 48, 96)) {
                    val localBoundary = playerControlsBlurBoundary(distance + navigation,
                        navigation, p, 208f, hidden, offset)
                    assertEquals(height - 208f + playerControlsTranslation(hidden, 208f),
                        windowTop - buffer + localBoundary, .001f)
                }
            }
        }
    }
}
