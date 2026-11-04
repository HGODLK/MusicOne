package com.musicone.source.kugou

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostRecommendationWindowTest {
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

}
