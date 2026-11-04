package com.musicone

import org.junit.Assert.*
import org.junit.Test

class RetainedPlayerLayoutTest {
    @Test fun hiddenPhoneUsesTheUpcomingDirectionForItsRetainedSubtree() {
        val publicDirection = 0
        for (direction in listOf(0, 90, -90)) {
            assertEquals(direction, retainedPlayerLayoutRotation(publicDirection, direction,
                mounted = false, retentionAllowed = true, defaultPhonePlayer = true))
        }
    }

    @Test fun movingOrShownPageKeepsTheActiveDirectionEvenIfTheSensorChanges() {
        for (active in listOf(0, 90, -90)) for (next in listOf(0, 90, -90)) {
            assertEquals(active, retainedPlayerLayoutRotation(active, next,
                mounted = true, retentionAllowed = true, defaultPhonePlayer = true))
        }
    }

    @Test fun tabletCustomPlayerAndReleasedRetentionKeepTheirOriginalLayoutPolicy() {
        assertEquals(0, retainedPlayerLayoutRotation(0, 90,
            mounted = false, retentionAllowed = true, defaultPhonePlayer = false))
        assertEquals(0, retainedPlayerLayoutRotation(0, 90,
            mounted = false, retentionAllowed = false, defaultPhonePlayer = true))
    }
}
