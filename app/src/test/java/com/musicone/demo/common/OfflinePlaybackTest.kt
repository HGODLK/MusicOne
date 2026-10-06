package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflinePlaybackTest {
    @Test fun localSourceRetainsAccountAndExactCacheKeyWithoutRemoteTicket() {
        val key = "QQ:用户+123|qq-song|/目录/RS01song.flac"
        val local = offlinePlaybackUrl(key)
        assertTrue(local.startsWith("musicone-cache://audio/RS01song.flac?"))
        assertEquals(key, offlinePlaybackKey(local))
        assertEquals(AudioQuality.HI_RES, qqPlaybackQuality(local))
        assertNull(offlinePlaybackKey("https://example.com/song.mp3?key=not-a-local-source"))
        assertNull(offlinePlaybackKey("musicone-cache://audio/song?key=%invalid"))
    }

    @Test fun completePendingCacheCanBeVerifiedLocallyWithoutAnotherTicket() {
        val record = JSONObject().put("pending", true).put("durationMs", 240_000)
        var saved: Boolean? = null
        assertTrue(verifyOfflineRecord(record, true, { 239_900L }, { saved = it }))
        assertEquals(true, saved)
    }

    @Test fun partialOrKnownTrialDoesNotReadMediaAndUnknownDurationStaysPending() {
        val pending = JSONObject().put("pending", true).put("durationMs", 240_000)
        val unexpectedRead = { error("不应读取不完整或已知试听音源") }
        val unexpectedSave: (Boolean) -> Unit = { error("不能将未验证缓存标记为完整") }
        assertFalse(verifyOfflineRecord(pending, false, unexpectedRead, unexpectedSave))
        assertFalse(verifyOfflineRecord(JSONObject().put("trial", true), true, unexpectedRead, unexpectedSave))
        assertFalse(verifyOfflineRecord(pending, true, { null }, unexpectedSave))
        var full: Boolean? = null
        assertFalse(verifyOfflineRecord(pending, true, { 30_000L }, { full = it }))
        assertEquals(false, full)
    }

    @Test fun verifiedCacheDoesNotRepeatExpensiveInspection() {
        assertTrue(verifyOfflineRecord(JSONObject().put("pending", false), true,
            { error("已校验缓存不应重复读取时长") }, { error("不应重复写入索引") }))
    }

    @Test fun badgeRequiresBothOfflineAndCompleteCacheMembership() {
        val offline = OfflinePlaybackAvailabilityState(false, setOf("qq-cached"))
        assertTrue(offline.showsBadge("qq-cached"))
        assertFalse(offline.showsBadge("qq-partial"))
        assertFalse(offline.copy(online = true).showsBadge("qq-cached"))
        assertFalse(offline.copy(trackIds = emptySet()).showsBadge("qq-cached"))
    }

    @Test fun lyricsCachePreservesTimingTextAndTranslationAndRejectsBrokenFiles() {
        val lyrics = listOf(TimedLyric(0, "前奏"), TimedLyric(1234, "晴天\n\"歌词\"", "Translation"))
        assertEquals(lyrics, decodeCachedLyrics(encodeCachedLyrics(lyrics)))
        assertTrue(decodeCachedLyrics("{broken").isEmpty())
        assertTrue(decodeCachedLyrics("[{\"text\":\"缺少时间\"}]").isEmpty())
    }
}
