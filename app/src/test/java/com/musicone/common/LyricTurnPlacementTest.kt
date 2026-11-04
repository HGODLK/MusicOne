package com.musicone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LyricTurnPlacementTest {
    private val portrait = LyricPlacementGeometry(1080, 1800, 500)
    private val landscape = LyricPlacementGeometry(1260, 900, 250)

    @Test fun retainedRowMotionIsSubtractedOnceFromTheFinalListOffset() = runBlocking {
        for (following in listOf(true, false)) {
            val position = LyricReadingPosition(7, -200f, 300, following, rowHeight = 800)
            val calls = mutableListOf<Pair<Int, Int>>()
            val retained = -23f
            assertTrue(placeLyricTurnReadingPosition(position, landscape,
                current = { true }, rowHeight = { 300 },
                scrollTo = { index, offset -> calls += index to offset }, nextFrame = {},
                rowMotionOffset = { retained }))
            val rawOffset = -calls.single().second
            assertEquals(position.targetOffset(landscape.height, 300).toFloat(), rawOffset + retained, .001f)
        }
    }

    @Test fun oldViewportAndOldDirectionCannotStartTargetPlacement() {
        assertFalse(lyricTurnViewportReady(landscape, portrait, 90, 90, true, true))
        assertFalse(lyricTurnViewportReady(landscape, landscape, 90, -90, true, true))
        assertFalse(lyricTurnViewportReady(landscape, landscape, 90, 90, true, false))
        assertFalse(lyricTurnViewportReady(landscape, landscape, 90, 90, false, true))
        assertFalse(lyricTurnViewportReady(landscape, landscape.copy(anchor = 500), 90, 90, true, true))
        assertTrue(lyricTurnViewportReady(landscape, landscape, 90, 90, true, true))
    }

    @Test fun followingWithoutMeasuredRowNeedsOnlyOneFinalPlacement() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        var frames = 0
        val result = placeLyricTurnReadingPosition(
            LyricReadingPosition(12, -85f, portrait.height, following = true), landscape,
            current = { true }, rowHeight = { null },
            scrollTo = { index, offset -> calls += index to offset }, nextFrame = { frames++ })
        assertTrue(result)
        assertEquals(listOf(12 to 0), calls)
        assertEquals(1, frames)
    }

    @Test fun measuredManualParagraphGoesDirectlyToItsFinalWrappedOffset() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val result = placeLyricTurnReadingPosition(
            LyricReadingPosition(7, -200f, 300, following = false, rowHeight = 800), landscape,
            current = { true }, rowHeight = { 300 },
            scrollTo = { index, offset -> calls += index to offset }, nextFrame = {})
        assertTrue(result)
        assertEquals(listOf(7 to 75), calls)
    }

    @Test fun unmeasuredManualParagraphMeasuresUnderCoverBeforeFinalPlacement() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        var height: Int? = null
        val result = placeLyricTurnReadingPosition(
            LyricReadingPosition(7, -200f, 300, following = false, rowHeight = 800), landscape,
            current = { true }, rowHeight = { height },
            scrollTo = { index, offset -> calls += index to offset }, nextFrame = { height = 300 })
        assertTrue(result)
        assertEquals(listOf(7 to 0, 7 to 75), calls)
    }

    @Test fun fallbackOrReversalDuringMeasurementCannotPublishOrScrollOldTargetAgain() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        var valid = true
        val result = placeLyricTurnReadingPosition(
            LyricReadingPosition(7, -200f, 300, following = false, rowHeight = 800), landscape,
            current = { valid }, rowHeight = { null },
            scrollTo = { index, offset -> calls += index to offset }, nextFrame = { valid = false })
        assertFalse(result)
        assertEquals(listOf(7 to 0), calls)
    }

    @Test fun fallbackDuringFinalDrawCannotConfirmTheOldPreparation() = runBlocking {
        var valid = true
        assertFalse(placeLyricTurnReadingPosition(
            LyricReadingPosition(12, 0f, 1800, following = true), landscape,
            current = { valid }, rowHeight = { 100 }, scrollTo = { _, _ -> },
            nextFrame = { valid = false }))
    }
}
