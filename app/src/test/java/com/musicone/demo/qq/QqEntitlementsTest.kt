package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqEntitlementsTest {
    private val now = 1_800_000_000_000L
    private fun song(pay: String, action: String = "{}") = JSONObject(
        """{"mid":"testmid","id":42,"name":"歌曲","pay":$pay,"action":$action}"""
    ).toQqTrack()

    private fun member(identity: String, extra: String = "") = JSONObject(
        """{"code":0,"membership":{"code":0,"data":{"identity":$identity$extra}}}"""
    )

    @Test fun nonMemberKeepsVipAndPaidAccordingToSongType() {
        assertEquals(MusicAccessBadge.VIP, song("""{"pay_play":1,"pay_month":1,"price_track":200}""")
            .withQqAccess("account", false, nowMs = now).accessBadge)
        assertEquals(MusicAccessBadge.PAID, song("""{"pay_play":1,"pay_month":0,"price_album":2000}""")
            .withQqAccess("account", false, nowMs = now).accessBadge)
    }

    @Test fun memberOnlyRemovesVipRequirement() {
        assertNull(song("""{"pay_play":1,"pay_month":1,"price_track":200}""")
            .withQqAccess("account", true, nowMs = now).accessBadge)
        assertEquals(MusicAccessBadge.PAID, song("""{"pay_play":1,"pay_month":0,"price_album":2000}""")
            .withQqAccess("account", true, nowMs = now).accessBadge)
    }

    @Test fun validPurchaseRemovesPaidForOwnerRegardlessOfMembership() {
        val track = song("""{"pay_play":1,"pay_month":0,"price_track":200,"pay_status":1}""")
        val purchased = track.copy(qqAccess = track.qqAccess!!.copy(permissionAccountId = "owner", checkedAtMs = now))
        assertNull(purchased.withQqAccess("owner", false, nowMs = now).accessBadge)
        assertNull(purchased.withQqAccess("owner", true, nowMs = now).accessBadge)
        assertEquals(MusicAccessBadge.PAID, purchased.withQqAccess("other", true, nowMs = now).accessBadge)
        assertEquals(MusicAccessBadge.PAID, purchased.withQqAccess("", false, nowMs = now).accessBadge)
    }

    @Test fun expiredOrUnboundPurchaseCannotRemovePaid() {
        val track = song("""{"pay_play":1,"pay_month":0,"price_track":200,"pay_status":1}""")
        assertEquals(MusicAccessBadge.PAID, track.withQqAccess("owner", true, nowMs = now).accessBadge)
        val old = track.copy(qqAccess = track.qqAccess!!.copy(permissionAccountId = "owner",
            checkedAtMs = now - QQ_ENTITLEMENT_TTL_MS))
        assertEquals(MusicAccessBadge.PAID, old.withQqAccess("owner", true, nowMs = now).accessBadge)
    }

    @Test fun membershipLossRestoresVipFromRawMetadata() {
        val track = song("""{"pay_play":1,"pay_month":1,"price_track":200}""")
        val entitled = track.withQqAccess("owner", true, nowMs = now)
        assertNull(entitled.accessBadge)
        assertEquals(MusicAccessBadge.VIP, entitled.withQqAccess("owner", false, nowMs = now).accessBadge)
    }

    @Test fun paidDownloadDoesNotMarkFreePlayback() {
        assertNull(song("""{"pay_play":0,"pay_down":1,"price_track":200}""").accessBadge)
    }

    @Test fun officialVipIconAndLegacyPayNamesAreRecognized() {
        assertEquals(MusicAccessBadge.VIP, song("{}", """{"icons":262144}""").accessBadge)
        assertEquals(MusicAccessBadge.VIP, song("""{"payplay":1,"paytrackmouth":1,"paytrackprice":200}""").accessBadge)
        assertEquals(MusicAccessBadge.PAID, song("""{"payplay":1,"paytrackmouth":0,"payalbumprice":2000}""").accessBadge)
    }

    @Test fun ordinaryMembershipDoesNotGrantSuperVipSong() {
        val track = song("{}", """{"icon2":2048}""")
        assertEquals(MusicAccessBadge.VIP, track.withQqAccess("owner", true, false, now).accessBadge)
        assertNull(track.withQqAccess("owner", true, true, now).accessBadge)
    }

    @Test fun absentAndMalformedPayFieldsRemainUnknown() {
        assertFalse(song("{}").qqAccess!!.complete)
        assertFalse(song("""{"pay_play":"bad","pay_month":null}""").qqAccess!!.complete)
        assertFalse(song("""{"pay_play":1}""").qqAccess!!.complete)
    }

    @Test fun snapshotPreservesRawTypeAndPurchaseOwner() {
        val track = song("""{"pay_play":1,"pay_month":0,"price_track":200,"pay_status":1}""")
        val entitled = track.copy(qqAccess = track.qqAccess!!.copy(permissionAccountId = "owner", checkedAtMs = now))
            .withQqAccess("owner", false, nowMs = now)
        val restored = entitled.toQqStoredTrackJson().toQqStoredTrack()!!
        assertEquals(entitled.qqAccess, restored.qqAccess)
        assertEquals(MusicAccessBadge.PAID, restored.withQqAccess("other", true, nowMs = now).accessBadge)
    }

    @Test fun playbackUrlPermissionDoesNotEraseBadges() {
        val paid = song("""{"pay_play":1,"pay_month":0,"price_track":200}""")
        val vip = song("""{"pay_play":1,"pay_month":1}""")
        assertEquals(MusicAccessBadge.PAID, paid.withQqPlaybackPermission(true).accessBadge)
        assertEquals(MusicAccessBadge.VIP, vip.withQqPlaybackPermission(true).accessBadge)
    }

    @Test fun genuineMemberAndExpiredMemberAreDistinguished() {
        assertTrue(parseQqMembership(member("""{"vip":1,"overdate":"2030-01-01"}"""), now)!!.vip)
        assertFalse(parseQqMembership(member("""{"vip":1,"overdate":"2020-01-01"}"""), now)!!.vip)
        assertFalse(parseQqMembership(member("""{"vip":0}"""), now)!!.vip)
    }

    @Test fun membershipErrorsAndMissingIdentityAreUnknown() {
        assertNull(parseQqMembership(JSONObject("""{"code":0,"membership":{"code":1,"data":{"identity":{"vip":1}}}}"""), now))
        assertNull(parseQqMembership(member("{}"), now))
        assertNull(parseQqMembership(JSONObject("""{"code":0,"membership":{"code":0,"data":{}}}"""), now))
    }

    @Test fun superVipFlagsAndExpirationUseActualResponse() {
        val valid = parseQqMembership(member("""{"vip":0,"HugeVip":1,"HugeVipEnd":"20300101120000"}"""), now)!!
        assertTrue(valid.vip)
        assertTrue(valid.superVip)
        assertFalse(parseQqMembership(member("""{"HugeVip":1,"HugeVipEnd":"bad"}"""), now)!!.vip)
    }

    @Test fun membershipCacheDoesNotOutliveNearestEntitlementExpiry() {
        val end = now + 10_000L
        val result = parseQqMembership(member("""{"vip":1,"overdate":"$end"}"""), now)!!
        assertEquals(end, result.validUntilMs)
    }

    @Test fun officialRequestsCarryActualAccountAndUseEntitlementEndpoint() {
        val cookie = "uin=12345; qqmusic_key=sample;"
        val request = qqMembershipRequest(cookie)
        assertEquals("12345", request.getJSONObject("comm").getString("uin"))
        assertEquals("sample", request.getJSONObject("comm").getString("authst"))
        assertEquals("VipLogin.VipLoginInter", request.getJSONObject("membership").getString("module"))
        assertEquals("vip_login_base", request.getJSONObject("membership").getString("method"))
    }

    @Test fun metadataQueryFailureKeepsKnownRestriction() {
        val track = song("""{"pay_play":1,"pay_month":0,"price_track":200}""")
        var requests = 0
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ ->
            requests += 1
            throw java.io.IOException("离线")
        })
        assertEquals(MusicAccessBadge.PAID, resolver.resolve(listOf(track), "uin=110001; qqmusic_key=sample;").single().accessBadge)
        assertEquals(1, requests)
    }

    @Test fun detailMustMatchRequestedSongBeforeAcceptingPurchase() {
        val track = song("""{"pay_play":1,"pay_month":0,"price_track":200}""")
        assertTrue(qqAccessDetailMatches(track, JSONObject("""{"mid":"testmid","id":42}""")))
        assertFalse(qqAccessDetailMatches(track, JSONObject("""{"mid":"other","id":42}""")))
        assertFalse(qqAccessDetailMatches(track, JSONObject("""{"mid":"testmid","type":2}""")))
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ -> JSONObject(
            """{"code":0,"access_0":{"code":0,"data":{"track_info":{"mid":"other","pay":{"pay_play":1,"pay_month":0,"price_track":200,"pay_status":1}}}}}"""
        ) })
        assertEquals(MusicAccessBadge.PAID, resolver.resolve(listOf(track), "uin=110002; qqmusic_key=sample;").single().accessBadge)
    }

    @Test fun authenticatedDetailPurchaseRemovesPaid() {
        val track = song("""{"pay_play":1,"pay_month":0,"price_track":200}""")
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ -> JSONObject(
            """{"code":0,"access_0":{"code":0,"data":{"track_info":{"mid":"testmid","pay":{"pay_play":1,"pay_month":0,"price_track":200,"pay_status":1}}}}}"""
        ) })
        val resolved = resolver.resolve(listOf(track), "uin=110003; qqmusic_key=sample;").single()
        assertNull(resolved.accessBadge)
        assertEquals("110003", resolved.qqAccess!!.permissionAccountId)
    }
    @Test fun freshSongTypeOverridesOlderPermissionCache() {
        val cookie = "uin=110004; qqmusic_key=sample;"
        val old = QqTrackAccessInfo(payPlay = 1, payMonth = 1, payStatus = 0,
            permissionAccountId = "110004", checkedAtMs = System.currentTimeMillis())
        QqEntitlements.remember(cookie, "qq-testmid", old)
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ -> JSONObject() })
        val track = song("""{"pay_play":1,"pay_month":0,"price_track":200}""")
        assertEquals(MusicAccessBadge.PAID, resolver.resolve(listOf(track), cookie).single().accessBadge)
    }

    @Test fun incompleteDetailsCannotEraseExistingRestriction() {
        val track = song("""{"pay_play":1,"pay_month":0,"price_track":200}""")
        val merged = track.mergeQqTrackMetadata(song("{}"))
        assertEquals(MusicAccessBadge.PAID, merged.accessBadge)
        assertFalse(merged.playable)
        assertEquals(track.qqAccess, merged.qqAccess)
    }

    @Test fun positiveMonthlyFlagRemainsVipEvenWithAlbumPrice() {
        assertEquals(MusicAccessBadge.VIP, song("""{"pay_play":1,"pay_month":2,"price_album":2000}""").accessBadge)
    }

    @Test fun malformedMembershipFlagIsUnknown() {
        assertNull(parseQqMembership(member("""{"vip":"bad"}"""), now))
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun cancelledPermissionQueryDoesNotContinueFallback() {
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ ->
            throw kotlinx.coroutines.CancellationException("取消")
        })
        resolver.resolve(listOf(song("{}")), "uin=110005; qqmusic_key=sample;")
    }

}
