package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackPlaybackAvailabilityTest {
    private val song = MusicTrack("qq-song", MusicSource.QQ, "歌曲", "歌手", "专辑",
        200_000, 0, 0, "歌", "", accessBadge = MusicAccessBadge.PAID)
    private val now = 1_800_000_000_000L
    private val denied = QqTrackAccessInfo(payPlay = 1, payMonth = 0, trackPrice = 200,
        payStatus = 0, permissionAccountId = "owner", checkedAtMs = now)

    @Test fun oldFlagsAndPaymentBadgesCannotDisableUnknownTracks() {
        for (source in listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU)) {
            val shown = displayedTrackAvailability(song.copy(source = source, playable = false,
                unavailableReason = "历史失败"), null, false)
            assertTrue(shown.playable)
            assertNull(shown.unavailableReason)
            assertEquals(MusicAccessBadge.PAID, shown.accessBadge)
        }
    }

    @Test fun onlyConfirmedDenialDisablesAndOfflineAudioTakesPriority() {
        assertFalse(displayedTrackAvailability(song, "明确无权限", false).playable)
        assertTrue(displayedTrackAvailability(song, "明确无权限", true).playable)
        val local = song.copy(source = MusicSource.LOCAL, playable = false, unavailableReason = "文件缺失")
        assertEquals(local, displayedTrackAvailability(local, null, false))
    }

    @Test fun offlineDisablesIncompleteAudioAndCompleteAudioOverridesOnlineDenial() {
        for (source in listOf(MusicSource.QQ, MusicSource.KUGOU, MusicSource.NETEASE, MusicSource.BILIBILI)) {
            val track = song.copy(source = source)
            assertFalse(displayedTrackAvailability(track, null, false, online = false).playable)
            val cached = displayedTrackAvailability(track, "在线无权限", true, online = false)
            assertTrue(cached.playable)
            assertNull(cached.unavailableReason)
        }
        val local = song.copy(source = MusicSource.LOCAL, playable = false)
        assertEquals(local, displayedTrackAvailability(local, null, true, online = false))
    }

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

    @Test fun emptyPlaybackUrlDoesNotProvePermissionOrResourceFailure() {
        try {
            neteasePlaybackSource(JSONObject().put("code", 200), AudioQuality.STANDARD)
            fail("空地址应报告可重试的取址失败")
        } catch (error: PlatformApiException) {
            assertFalse(error is TrackUnavailableException)
        }
    }

    @Test fun returningToPurchasedSongRefreshesWithoutWaitingForOldFailureToExpire() = runBlocking {
        var requests = 0
        val store = TrackAvailabilityStore(this) { requests++; null }
        store.report(song, 0, "未购买")
        store.refresh(song)
        assertEquals(1, requests)
        assertTrue(store.state.value.unavailable.isEmpty())
    }

    @Test fun unknownRefreshClearsOldDenialAndConfirmedRefreshCanRestoreIt() = runBlocking {
        val store = TrackAvailabilityStore(this) { throw java.io.IOException("暂时离线") }
        store.report(song, 0, "旧权限")
        store.refresh(song)
        assertTrue(store.state.value.unavailable.isEmpty())
        val deniedStore = TrackAvailabilityStore(this) { "当前仍无权限" }
        deniedStore.report(song, 0, "旧权限")
        deniedStore.refresh(song)
        assertEquals("当前仍无权限", deniedStore.state.value.unavailable[song.id])
    }

    @Test fun duplicateDisplayChecksShareOneRequest() = runBlocking {
        var requests = 0
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String?>()
        val store = TrackAvailabilityStore(this) { requests++; entered.complete(Unit); response.await() }
        val legacy = song.copy(playable = false)
        store.report(legacy, 0, "旧权限")
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(legacy) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(legacy) }
        response.complete(null)
        first.await()
        second.await()
        assertEquals(1, requests)
    }

    @Test fun accountChangeRejectsLateCheckAndLatePlaybackResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String?>()
        val store = TrackAvailabilityStore(this) { entered.complete(Unit); response.await() }
        store.report(song, 0, "旧账号无权限")
        val check = async { store.refresh(song) }
        entered.await()
        store.reset()
        response.complete("旧账号仍无权限")
        check.await()
        store.report(song, 0, "迟到的播放失败")
        assertEquals(1L, store.state.value.revision)
        assertTrue(store.state.value.unavailable.isEmpty())
    }

    @Test fun successfulPlaybackWinsOverLateDisplayCheck() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String?>()
        val store = TrackAvailabilityStore(this) { entered.complete(Unit); response.await() }
        store.report(song, 0, "旧权限")
        val check = async { store.refresh(song) }
        entered.await()
        store.report(song, 0, null)
        response.complete("迟到的无权限结果")
        check.await()
        assertTrue(store.state.value.unavailable.isEmpty())
    }

    @Test fun forcedQqCheckBypassesCachedDenialAndFailedCheckCannotReuseIt() {
        val cookie = "uin=880001; qqmusic_key=sample;"
        val track = song.copy(songMid = "song", qqAccess = denied.copy(permissionAccountId = "880001",
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
        val current = resolver.resolve(listOf(song.copy(songMid = "song")), cookie,
            fresh = false, forceRefresh = true).single()
        assertNull(confirmedQqPlaybackRestriction(current.qqAccess, "880002", null))
        assertNull(current.accessBadge)
        assertEquals(1, QqEntitlements.cached(cookie, song.id)!!.payStatus)
        assertTrue(current.playable)
        QqEntitlements.forget(cookie)
    }
}
