package com.musicone.demo

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueSongMenuPlacementTest {
    @Test fun topSongPlacesMenuBelowTheWholeRow() {
        val row = Rect(16f, 70f, 384f, 140f)
        val region = queueSongMenuRegion(400f, 600f, row, 12f, 8f)
        assertFalse(region.above)
        assertEquals(148f, region.bounds.top)
        assertFalse(region.bounds.overlaps(row))
    }

    @Test fun bottomSongPlacesMenuAboveTheWholeRow() {
        val row = Rect(16f, 480f, 624f, 554f)
        val region = queueSongMenuRegion(640f, 600f, row, 12f, 8f)
        assertTrue(region.above)
        assertEquals(472f, region.bounds.bottom)
        assertFalse(region.bounds.overlaps(row))
    }

    @Test fun shortWindowConstrainsLongMenuToTheLargerAvailableSide() {
        val row = Rect(16f, 120f, 624f, 210f)
        val region = queueSongMenuRegion(640f, 300f, row, 12f, 8f)
        assertTrue(region.above)
        assertEquals(Rect(12f, 12f, 628f, 112f), region.bounds)
        assertFalse(region.bounds.overlaps(row))
    }

    @Test fun partiallyVisibleSongsUseSpaceInsideTheQueueViewport() {
        assertFalse(queueSongMenuRegion(400f, 600f, Rect(16f, -20f, 384f, 50f), 12f, 8f).above)
        assertTrue(queueSongMenuRegion(400f, 600f, Rect(16f, 560f, 384f, 640f), 12f, 8f).above)
    }
}
