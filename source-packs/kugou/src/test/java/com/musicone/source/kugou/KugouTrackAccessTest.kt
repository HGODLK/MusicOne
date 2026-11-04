package com.musicone.source.kugou

import com.musicone.source.sdk.SourcePayload
import com.musicone.source.sdk.SourceTrack
import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.sdk.SourceTrackBadge
import com.musicone.source.sdk.TrackAccessBadge
import com.musicone.source.sdk.TrackAccessInfo
import com.musicone.source.sdk.TrackIdentity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouTrackAccessTest {
    private val cookie = "userid=42; token=test-token; KUGOU_API_MID=test-mid"
    private fun track(access: TrackAccessInfo = TrackAccessInfo()) = SourceTrackDetails(
        SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, "42", "standard-hash"), "歌曲", opaqueData = mapOf(
            "catalogId" to "88", "mediaId" to "99", "qualityIds" to "{\"STANDARD\":\"standard-hash\"}",
        )), "kugou-standard-hash", access = access, payload = SourcePayload("kugou.track", 1, "{}"),
    )
    private fun response(item: JSONObject) = JSONObject().put("status", 1).put("error_code", 0)
        .put("data", JSONArray().put(item))
    private fun detail(payType: Int) = JSONObject().put("hash", "standard-hash")
        .put("album_audio_id", 88).put("quality", "128").put("pay_type", payType)

    @Test fun samuelKimLorienSearchAndPrivilegeWithoutPrivilegeFieldAgree() {
        // 2026-10-28 公开搜索的真实歌曲字段；不保存账号资料或播放地址。
        val song = JSONObject("""{"SongName":"I Really Want to Stay at Your House",
            "SingerName":"Samuel Kim、Lorien","MixSongID":451097066,
            "FileHash":"ECEEBBF0B82CAD5CBBF11E889136961C",
            "PayType":1,"Privilege":10,"FailProcess":4}""")
        assertNull(song.toKugouTrack(true).accessBadge)
        assertEquals(MusicAccessBadge.VIP, song.toKugouTrack(false).accessBadge)
        val old = track(TrackAccessInfo(TrackAccessBadge.PURCHASE,
            displayBadge = SourceTrackBadge("付费"))).withKugouRawAccess(
            KugouTrackAccessInfo(false, true, false, true, classificationVersion = 4))
        val resolver = KugouTrackAccessResolver { _, _ -> response(detail(1).put("fail_process", 4)) }
        val member = resolver.access(old, cookie, true)
        assertNull(member.badge)
        assertNull(member.displayBadge)
        assertFalse(member.trialAvailable)
        assertEquals(TrackAccessBadge.MEMBERSHIP, resolver.access(old, cookie, false).badge)
    }

    @Test fun payTypeBitsKeepMembershipAlternativeAndDoNotInventPurchaseForOtherBits() {
        for (payType in listOf(1, 3, 5)) {
            val raw = JSONObject().put("pay_type", payType).kugouTrackAccessInfo()!!
            assertNull(raw.access("42", true).badge)
            assertEquals(MusicAccessBadge.VIP, raw.access("42", false).badge)
        }
        assertEquals(MusicAccessBadge.PAID,
            JSONObject().put("pay_type", 2).kugouTrackAccessInfo()!!.access("42", true).badge)
        assertNull(JSONObject().put("pay_type", 4).kugouTrackAccessInfo()!!.access("42", false).badge)
    }

    @Test fun unknownFieldsRemainUnknownAndExplicitFreeIsKnown() {
        assertNull(JSONObject().kugouTrackAccessInfo())
        assertNull(JSONObject().put("is_buy", 0).kugouTrackAccessInfo())
        assertNull(JSONObject().put("is_buy", 1).kugouTrackAccessInfo())
        val free = JSONObject().put("PayType", 0).kugouTrackAccessInfo()
        assertNotNull(free)
        assertNull(free!!.access("42", false).badge)
    }

    @Test fun membershipChangeRestoresVipBadgeFromRawInformation() {
        val raw = JSONObject().put("PayType", 3).put("Privilege", 8).kugouTrackAccessInfo()!!
        assertNull(raw.access("42", true).badge)
        assertEquals(MusicAccessBadge.VIP, raw.access("42", false).badge)
        assertTrue(raw.access("42", false).trialAvailable)
        assertFalse(raw.access("42", true).trialAvailable)
        assertTrue(raw.access("42", false).playable)
    }

    @Test fun feeAttributeDoesNotOverrideExplicitMusicMembershipOrFreeStandard() {
        val vip = JSONObject().put("pay_type", 3).put("feetype", 1).kugouTrackAccessInfo()!!
        assertFalse(vip.paidOnly)
        assertNull(vip.access("42", true).badge)
        assertEquals(MusicAccessBadge.VIP, vip.access("42", false).badge)
        val free = JSONObject().put("pay_type", 0).put("feetype", 1).kugouTrackAccessInfo()!!
        assertNull(free.access("42", false).badge)
        assertNull(JSONObject().put("feetype", 1).kugouTrackAccessInfo())
    }

    @Test fun legacyPaidSnapshotIsReclassifiedFromMatchingStandardMetadata() {
        val legacy = JSONObject().put("purchased", false).put("paidOnly", true)
            .put("needsVip", true).put("trialAvailable", true).toKugouTrackAccessInfo()
        assertEquals(1, legacy.classificationVersion)
        val snapshot = track(TrackAccessInfo(TrackAccessBadge.PURCHASE)).withKugouRawAccess(legacy)
        val resolver = KugouTrackAccessResolver { _, _ -> response(detail(3).put("feetype", 1)) }
        assertNull(resolver.access(snapshot, cookie, true).badge)
        assertEquals(TrackAccessBadge.MEMBERSHIP, resolver.access(snapshot, cookie, false).badge)
        val paidSnapshot = snapshot.withKugouRawAccess(legacy.copy(needsVip = false))
        val paid = KugouTrackAccessResolver { _, _ -> response(detail(2)) }
        assertEquals(TrackAccessBadge.PURCHASE, paid.access(paidSnapshot, cookie, true).badge)
    }

    @Test fun membershipDoesNotRemovePurchaseBadgeAndPurchasesBelongToOneAccount() {
        val raw = JSONObject().put("PayType", 2).put("is_buy", 1).kugouTrackAccessInfo()!!.bindPurchase("42")
        assertNull(raw.access("42", false).badge)
        assertEquals(MusicAccessBadge.PAID, raw.access("43", true).badge)
        assertEquals("42", raw.bindPurchase("43").purchaseAccountId)
    }

    @Test fun rawAccessSurvivesPayloadAndOpaqueRoundTrips() {
        val raw = KugouTrackAccessInfo(false, false, true, true, "42")
        val encoded = track().withKugouRawAccess(raw)
        assertEquals(raw, encoded.kugouRawAccess())
        assertEquals(raw, encoded.copy(track = encoded.track.copy(opaqueData = emptyMap())).kugouRawAccess())
        assertEquals(raw, raw.toJson().toKugouTrackAccessInfo())
    }

    @Test fun mapperKeepsRestrictionsWhenCurrentMembershipHidesBadge() {
        val parsed = JSONObject().put("hash", "standard-hash").put("PayType", 3).toKugouTrack(true)
        assertNull(parsed.accessBadge)
        val details = KugouSourceMapping.track(parsed, "42")
        assertTrue(details.kugouRawAccess()!!.needsVip)
        assertEquals(parsed.kugouAccess, KugouSourceMapping.track(details).kugouAccess)
        val resolver = KugouTrackAccessResolver { _, _ -> error("原始信息已保存") }
        assertEquals(TrackAccessBadge.MEMBERSHIP, resolver.access(details, cookie, false).badge)
    }

    @Test fun knownRawDoesNotNeedDetailsAndPreservesPlaybackStatus() {
        val original = TrackAccessInfo(playable = false, reason = "原始播放状态")
        val known = track(original).withKugouRawAccess(KugouTrackAccessInfo(false, false, true, true))
        val resolver = KugouTrackAccessResolver { _, _ -> error("已有原始信息不能重复补查") }
        val access = resolver.access(known, cookie, false)
        assertEquals(TrackAccessBadge.MEMBERSHIP, access.badge)
        assertFalse(access.playable)
        assertEquals("原始播放状态", access.reason)
    }

    @Test fun oldSnapshotCanRecoverVipBadgeFromMatchingStandardDetails() {
        var calls = 0
        val resolver = KugouTrackAccessResolver { _, sentCookie ->
            calls++
            assertEquals(cookie, sentCookie)
            response(detail(3))
        }
        assertEquals(TrackAccessBadge.MEMBERSHIP, resolver.access(track(), cookie, false).badge)
        assertEquals(1, calls)
    }

    @Test fun failedOrMismatchedDetailsKeepOriginalAccess() {
        val original = TrackAccessInfo(TrackAccessBadge.MEMBERSHIP, true, false, "原始状态")
        val snapshot = track(original)
        val failed = KugouTrackAccessResolver { _, _ -> throw java.io.IOException("暂时断网") }
        assertSame(original, failed.access(snapshot, cookie, true))
        val mismatched = KugouTrackAccessResolver { _, _ -> response(detail(0).put("hash", "another-song")) }
        assertSame(original, mismatched.access(snapshot, cookie, true))
        val missing = KugouTrackAccessResolver { _, _ -> response(detail(0).apply { remove("pay_type") }) }
        assertSame(original, missing.access(snapshot, cookie, true))
    }

    @Test fun restrictedHigherQualityDoesNotTagFreeStandardQuality() {
        val higher = detail(3).put("hash", "flac-hash").put("quality", "flac")
        val freeStandard = detail(0)
        val value = MusicTrack("kugou-flac-hash", title = "歌曲", artists = "歌手", catalogId = "88",
            qualityIds = mapOf(AudioQuality.STANDARD to "standard-hash", AudioQuality.LOSSLESS to "flac-hash"))
        higher.put("relate_goods", JSONArray().put(freeStandard))
        val chosen = response(higher).kugouStandardAccessDetail(value)
        assertSame(freeStandard, chosen)
        assertNull(chosen!!.kugouTrackAccessInfo()!!.access("42", false).badge)
        freeStandard.put("hash", "another-standard-song")
        assertNull(response(higher).kugouStandardAccessDetail(value))
        higher.remove("relate_goods")
        assertNull(response(higher).kugouStandardAccessDetail(value))
    }
}
