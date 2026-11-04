package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class MigratedHostRecommendationOffsetTest {
    @Test fun qqPlaylistRefreshAdvancesTheAccountFeedOffset() {
        assertEquals(0, qqRecommendationOffset(0))
        assertEquals(8, qqRecommendationOffset(1))
        assertEquals(24, qqRecommendationOffset(3))
    }
}
