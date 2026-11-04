package com.musicone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MusicCatalogRefreshTest {
    @Test fun refreshRunsForEmptyForcedOrStaleRecommendations() {
        assertTrue(recommendedTracksRefreshDue(false, false, "2026-10-07", "2026-10-07"))
        assertTrue(recommendedTracksRefreshDue(true, true, "2026-10-07", "2026-10-07"))
        assertTrue(recommendedTracksRefreshDue(false, true, "2026-10-06", "2026-10-07"))
    }

    @Test fun recentRecommendationsAreReusedUntilRefreshIsDue() {
        assertFalse(recommendedTracksRefreshDue(false, true, "2026-10-07", "2026-10-07"))
    }

}
