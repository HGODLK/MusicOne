package com.musicone.source.netease

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseTrackAccessTest {
    @Test fun trialOnlyVipTrackShowsVipAndRemainsPlayable() {
        val access = neteaseTrackAccess(1, 0, 128_000, false, 0)

        assertEquals(NeteaseAccessBadge.VIP, access.badge)
        assertTrue(access.trialAvailable)
        assertTrue(access.playable)
    }

    @Test fun entitledVipTrackDoesNotShowMarker() {
        val access = neteaseTrackAccess(1, 320_000, 0, false, 0)

        assertNull(access.badge)
        assertFalse(access.trialAvailable)
        assertTrue(access.playable)
    }

    @Test fun vipAccountDoesNotShowMarkerWhenPrivilegeBitrateIsMissing() {
        val access = neteaseTrackAccess(1, 0, 0, false, 0, hasVipAccess = true)

        assertNull(access.badge)
        assertTrue(access.playable)
    }

    @Test fun unpaidDigitalAlbumTrackShowsPaidMarker() {
        val access = neteaseTrackAccess(4, 0, 128_000, false, 0)

        assertEquals(NeteaseAccessBadge.PAID, access.badge)
        assertTrue(access.trialAvailable)
    }

    @Test fun purchasedDigitalAlbumTrackDoesNotShowMarker() {
        val access = neteaseTrackAccess(4, 320_000, 0, false, 0)

        assertNull(access.badge)
    }

}
