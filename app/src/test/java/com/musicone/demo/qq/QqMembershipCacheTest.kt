package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqMembershipCacheTest {
    private val now = 1_800_000_000_000L
    private val expiry = now + 24 * 60 * 60 * 1_000L
    private fun member(vipEnd: Long = expiry, superEnd: Long = 0L): QqMembership = parseQqMembership(
        JSONObject("""{"code":0,"membership":{"code":0,"data":{"identity":{"vip":1,"overdate":"$vipEnd","HugeVip":1,"HugeVipEnd":"$superEnd"}}}}"""), now)!!

    @Test fun backgroundPastCacheExpiryKeepsConfirmedMembershipWhileRefreshingAndAfterFailure() {
        val cache = QqMembershipCache()
        cache.remember(member(), now)
        val resumedAt = now + 2 * 60 * 60 * 1_000L
        assertTrue(cache.begin(resumedAt))
        assertTrue(cache.current(resumedAt)!!.vip)
        assertFalse(cache.begin(resumedAt))
        cache.complete(null, resumedAt)
        assertTrue(cache.current(resumedAt)!!.vip)
        assertFalse(cache.loading)
        assertEquals(resumedAt + 30_000L, cache.nextRefreshMs(resumedAt))
        assertFalse(cache.begin(resumedAt + 1_000L))
        cache.resume()
        assertTrue(cache.begin(resumedAt + 1_000L))
    }

    @Test fun confirmedLossAndRealExpiryRestoreVipInsteadOfExtendingOldMembership() {
        val cache = QqMembershipCache()
        cache.remember(member(), now)
        cache.complete(null, expiry)
        assertFalse(cache.current(expiry)!!.vip)
        cache.complete(QqMembership(false, validUntilMs = expiry + QQ_ENTITLEMENT_TTL_MS), expiry)
        assertFalse(cache.current(expiry)!!.vip)
        assertFalse(cache.begin(expiry + 1))
    }

    @Test fun superVipExpiryDoesNotRemoveStillValidOrdinaryMembership() {
        val superEnd = now + 10_000L
        val membership = member(superEnd = superEnd)
        assertEquals(superEnd, membership.validUntilMs)
        val after = membership.effectiveAt(superEnd)
        assertTrue(after.vip)
        assertFalse(after.superVip)
        val superSong = QqTrackAccessInfo(icon2 = 2048L)
        assertEquals(MusicAccessBadge.VIP, superSong.access("owner", after.vip, after.superVip, superEnd).badge)
        assertNull(QqTrackAccessInfo(payMonth = 1).access("owner", after.vip, after.superVip, superEnd).badge)
    }

    @Test fun unknownQueryCannotEraseConfirmedAccountAndAnotherAccountCannotReuseIt() {
        val owner = QqMembershipCache()
        val other = QqMembershipCache()
        owner.remember(member(), now)
        owner.remember(null, now + 1)
        assertTrue(owner.current(now + 1)!!.vip)
        assertNull(other.current(now + 1))
        other.complete(QqMembership(false, validUntilMs = now + QQ_ENTITLEMENT_TTL_MS), now)
        assertFalse(other.current(now + 1)!!.vip)
        assertTrue(owner.current(now + 1)!!.vip)
    }

    @Test fun missingRealExpiryHasBoundedRetentionAndStillRefreshesEveryFiveMinutes() {
        val membership = parseQqMembership(JSONObject(
            """{"code":0,"membership":{"code":0,"data":{"identity":{"vip":1}}}}"""), now)!!
        assertEquals(now + QQ_ENTITLEMENT_TTL_MS, membership.validUntilMs)
        assertTrue(membership.effectiveAt(now + QQ_ENTITLEMENT_TTL_MS + 1).vip)
        assertFalse(membership.effectiveAt(now + QQ_MEMBERSHIP_UNKNOWN_EXPIRY_GRACE_MS).vip)
    }

    @Test fun successfulRefreshCanRevokeMembershipBeforePreviouslyReportedExpiry() {
        val cache = QqMembershipCache()
        cache.remember(member(), now)
        cache.complete(QqMembership(false, validUntilMs = now + 2 * QQ_ENTITLEMENT_TTL_MS), now + QQ_ENTITLEMENT_TTL_MS)
        assertFalse(cache.current(now + QQ_ENTITLEMENT_TTL_MS)!!.vip)
    }
}
