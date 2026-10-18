package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricResidencyTest {
    @Test fun bothPackagesKeepTheSameReadingCoordinatesWhileClosed() {
        for (continuous in listOf(false, true)) {
            val state = LyricResidency(true, true, true, continuous)
            assertTrue(state.prepareClosed)
            assertEquals(continuous, state.keepRefreshing)
            assertEquals(!continuous, state.pauseWords)
        }
    }

    @Test fun openingAndDraggingUseNormalAnimationWithoutAnExtraRefreshLoop() {
        for (continuous in listOf(false, true)) {
            val state = LyricResidency(true, true, false, continuous)
            assertFalse(state.prepareClosed)
            assertFalse(state.keepRefreshing)
            assertFalse(state.pauseWords)
        }
    }

    @Test fun hiddenBackgroundAndLowMemoryCannotKeepTheExperimentRunning() {
        assertFalse(LyricResidency(true, false, true, true).keepRefreshing)
        assertFalse(LyricResidency(false, true, true, true).keepRefreshing)
        assertFalse(LyricResidency.None.prepareClosed)
        assertFalse(LyricResidency.None.pauseWords)
    }
}
