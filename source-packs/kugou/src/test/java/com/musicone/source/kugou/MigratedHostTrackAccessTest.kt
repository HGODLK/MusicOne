package com.musicone.source.kugou

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostTrackAccessTest {
    @Test fun missingKugouHashRemainsRetryableForResourceRecovery() {
        val track = JSONObject().put("MixSongID", "12345").put("SongName", "失效歌曲")
            .toKugouTrack(hasVipAccess = true)
        assertEquals("kugou-missing-12345", track.id)
        assertTrue(track.playable)
        assertNull(track.unavailableReason)
    }

    @Test fun kugouVipEntitlementHidesVipMarker() {
        val access = kugouTrackAccess(
            purchased = false,
            paidOnly = false,
            needsVip = true,
            hasVipAccess = true,
            trialAvailable = true,
        )

        assertNull(access.badge)
        assertTrue(access.playable)
        assertFalse(access.trialAvailable)
    }

    @Test fun kugouUnpurchasedSongKeepsPaidMarkerEvenForVip() {
        val access = kugouTrackAccess(
            purchased = false,
            paidOnly = true,
            needsVip = false,
            hasVipAccess = true,
            trialAvailable = true,
        )

        assertEquals(MusicAccessBadge.PAID, access.badge)
        assertTrue(access.trialAvailable)
    }

}
