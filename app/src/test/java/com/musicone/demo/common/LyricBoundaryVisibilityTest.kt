package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricBoundaryVisibilityTest {
    @Test fun partiallyClippedGlyphsAreHiddenAtBothEdges() {
        assertEquals(0f, lyricBoundaryLineAlpha(-1f, 39f, 0f, 200f, 8f), 0f)
        assertEquals(0f, lyricBoundaryLineAlpha(161f, 201f, 0f, 200f, 8f), 0f)
        assertEquals(1f, lyricBoundaryLineAlpha(20f, 60f, 0f, 200f, 8f), 0f)
    }

    @Test fun spatialFadeIsReversibleAndUsesTheTransformedLineBounds() {
        val samples = (0..8).map { lyricBoundaryLineAlpha(it.toFloat(), 40f + it, 0f, 200f, 8f) }
        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0f)
        assertTrue(samples.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(.5f, lyricBoundaryLineAlpha(4f, 44f, 0f, 200f, 8f), 0f)
        assertEquals(.5f, lyricBoundaryLineAlpha(24f, 64f, 20f, 220f, 8f), 0f)
    }

    @Test fun newlyFillingTopLineFadesInOnlyAfterItsWholeLayoutLineClearsTheEdge() {
        val samples = (-2..10).map { lyricBoundaryLineAlpha(it.toFloat(), it + 40f,
            0f, 200f, 8f) }
        assertEquals(0f, samples.first(), 0f)
        assertEquals(1f, samples.last(), 0f)
        assertTrue(samples.zipWithNext().all { (before, after) -> after >= before })
    }
}
