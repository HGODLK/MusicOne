package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LyricBoundaryVisibilityTest {
    @Test fun landscapePlayedLineRemainsUntilItsBottomLeavesTheViewport() {
        for (top in listOf(12f, 0f, -1f, -39f)) {
            assertEquals(1f, lyricBoundaryLineAlpha(top, top + 40f, 0f, 200f, 8f,
                partialTop = true), 0f)
        }
        assertEquals(0f, lyricBoundaryLineAlpha(-40f, 0f, 0f, 200f, 8f, partialTop = true), 0f)
        assertEquals(0f, lyricBoundaryLineAlpha(-41f, -1f, 0f, 200f, 8f, partialTop = true), 0f)
        assertEquals(.5f, lyricBoundaryLineAlpha(196f, 236f, 0f, 200f, 8f, partialTop = true), 0f)
    }

    @Test fun missingPlacementDoesNotHideTheOriginalGlyphButConfirmedOutsideStillDoes() {
        assertTrue(lyricBoundaryDrawsContent(emptyList()))
        assertTrue(lyricBoundaryDrawsContent(listOf(1f, 1f)))
        assertFalse(lyricBoundaryDrawsContent(listOf(0f, 0f)))
        assertFalse(lyricBoundaryDrawsContent(listOf(1f, .5f)))
    }

    @Test fun onlyPlainPortraitMotionCachesBoundaryAlphas() {
        assertTrue(lyricBoundaryCachesAlphas(false, false, false, true))
        assertFalse(lyricBoundaryCachesAlphas(true, false, false, true))
        assertFalse(lyricBoundaryCachesAlphas(false, true, false, true))
        assertFalse(lyricBoundaryCachesAlphas(false, false, true, true))
        assertFalse(lyricBoundaryCachesAlphas(false, false, false, false))
    }

    @Test fun topProtectsWholeGlyphsWhileBottomAllowsSlidingIntoTheViewport() {
        assertEquals(0f, lyricBoundaryLineAlpha(-1f, 39f, 0f, 200f, 8f), 0f)
        assertEquals(1f, lyricBoundaryLineAlpha(161f, 201f, 0f, 200f, 8f), 0f)
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

    @Test fun bottomLineStartsEnteringBeforeTheWholeGlyphHasPassedTheEdge() {
        val samples = (0..8).map { distance ->
            lyricBoundaryLineAlpha(200f - distance, 240f - distance, 0f, 200f, 8f)
        }
        assertEquals(0f, samples.first(), 0f)
        assertEquals(.5f, samples[4], 0f)
        assertEquals(1f, samples.last(), 0f)
        assertTrue(samples.zipWithNext().all { (before, after) -> after >= before })
        assertEquals(0f, lyricBoundaryLineAlpha(201f, 241f, 0f, 200f, 8f), 0f)
    }
}
