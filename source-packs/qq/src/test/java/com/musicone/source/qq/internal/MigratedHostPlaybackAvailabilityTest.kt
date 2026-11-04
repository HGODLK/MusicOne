package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostPlaybackAvailabilityTest {
    private val song = MusicTrack("qq-song", MusicSource.QQ, "歌曲", "歌手", "专辑",
        200_000, 0, 0, "歌", "", accessBadge = MusicAccessBadge.PAID)
    private val now = 1_800_000_000_000L
    private val denied = QqTrackAccessInfo(payPlay = 1, payMonth = 0, trackPrice = 200,
        payStatus = 0, permissionAccountId = "owner", checkedAtMs = now)

    @Test fun purchaseCheckMustBelongToCurrentAccountAndCurrentTime() {
        assertNotNull(confirmedQqPlaybackRestriction(denied, "owner", null, now))
        for (info in listOf(denied.copy(payStatus = null), denied.copy(payStatus = 1),
            denied.copy(permissionAccountId = "other"), denied.copy(checkedAtMs = now - QQ_ENTITLEMENT_TTL_MS),
            denied.copy(trial = true), denied.copy(checkedAtMs = now + 1))) {
            assertNull(confirmedQqPlaybackRestriction(info, "owner", null, now))
        }
        assertNull(confirmedQqPlaybackRestriction(denied, "", null, now))
    }

    @Test fun unknownOrStaleMembershipDoesNotProveVipSongIsDenied() {
        val vip = denied.copy(payMonth = 1)
        assertNull(confirmedQqPlaybackRestriction(vip, "owner", null, now))
        assertNull(confirmedQqPlaybackRestriction(vip, "owner", QqMembership(false, validUntilMs = now), now))
        assertNull(confirmedQqPlaybackRestriction(vip, "owner", QqMembership(true, validUntilMs = now + 1_000), now))
        assertNotNull(confirmedQqPlaybackRestriction(vip, "owner", QqMembership(false, validUntilMs = now + 1_000), now))
    }

    @Test fun forcedQqCheckBypassesCachedDenialAndFailedCheckCannotReuseIt() {
        val cookie = "uin=880001; qqmusic_key=sample;"
        val track = song.withQqSongMid("song").withQqAccessInfo(denied.copy(permissionAccountId = "880001",
            checkedAtMs = System.currentTimeMillis()))
        QqEntitlements.remember(cookie, song.id, track.qqAccess!!)
        var requests = 0
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ ->
            requests++
            throw java.io.IOException("核对失败")
        })
        val result = resolver.resolve(listOf(track), cookie, fresh = false, forceRefresh = true).single()
        assertEquals(1, requests)
        assertNull(confirmedQqPlaybackRestriction(result.qqAccess, "880001", null))
        assertTrue(result.playable)
        QqEntitlements.forget(cookie)
    }

    @Test fun freshPurchaseReplacesCachedDenialForTheSameAccount() {
        val cookie = "uin=880002; qqmusic_key=sample;"
        QqEntitlements.remember(cookie, song.id, denied.copy(permissionAccountId = "880002",
            checkedAtMs = System.currentTimeMillis()))
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ -> JSONObject("""
            {"access_0":{"code":0,"data":{"track_info":{"mid":"song",
              "pay":{"pay_play":1,"pay_month":0,"price_track":200,"pay_status":1}}}}}
        """) })
        val current = resolver.resolve(listOf(song.withQqSongMid("song")), cookie,
            fresh = false, forceRefresh = true).single()
        assertNull(confirmedQqPlaybackRestriction(current.qqAccess, "880002", null))
        assertNull(current.accessBadge)
        assertEquals(1, QqEntitlements.cached(cookie, song.id)!!.payStatus)
        assertTrue(current.playable)
        QqEntitlements.forget(cookie)
    }
}
