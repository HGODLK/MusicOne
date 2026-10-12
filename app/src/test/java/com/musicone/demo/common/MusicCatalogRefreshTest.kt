package com.musicone.demo

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

    @Test fun refreshMovesToNextPlatformRecommendationBatch() {
        val pool = (1..30).toList()
        assertEquals((1..12).toList(), recommendationWindow(pool, 12, 0))
        assertEquals((13..24).toList(), recommendationWindow(pool, 12, 1))
    }

    @Test fun aShortDailyPoolStillChangesOrderOnRefresh() {
        val pool = (1..12).toList()
        assertNotEquals(recommendationWindow(pool, 12, 0), recommendationWindow(pool, 12, 1))
        assertEquals(pool.toSet(), recommendationWindow(pool, 12, 1).toSet())
    }

    @Test fun qqPlaylistRefreshAdvancesTheAccountFeedOffset() {
        assertEquals(0, qqRecommendationOffset(0))
        assertEquals(8, qqRecommendationOffset(1))
        assertEquals(24, qqRecommendationOffset(3))
    }
}
