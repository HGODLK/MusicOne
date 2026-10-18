package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouPlaybackResourceTest {
    private fun track() = MusicTrack("kugou-base", MusicSource.KUGOU, "歌曲", "歌手", "专辑",
        240_000, 0, 0, "曲", "", mediaId = "12", catalogId = "old",
        qualityIds = mapOf(AudioQuality.STANDARD to "base"))

    @Test fun resourceQueryRequestsOnlyTheTargetTierWithoutAlbumAudioId() {
        val request = kugouResourceRequest(track(), AudioQuality.LOSSLESS)
        assertEquals("[\"flac\"]", request.getJSONArray("qualities").toString())
        assertFalse(request.getJSONArray("resource").getJSONObject(0).has("album_audio_id"))
    }

    @Test fun resourceEnrichmentPreservesIdentityAndOtherHashes() {
        val original = track()
        val response = JSONObject("""{"data":[{"album_audio_id":123,"album_id":45,"relate_goods":[
            {"quality":"320","hash":"high"},{"quality":"flac","hash":"lossless"}]}]}""")
        val enriched = response.withKugouPlaybackResource(original, AudioQuality.LOSSLESS)
        assertEquals(original.id, enriched.id)
        assertEquals("base", enriched.qualityIds[AudioQuality.STANDARD])
        assertEquals("lossless", enriched.qualityIds[AudioQuality.LOSSLESS])
        assertNull(enriched.qualityIds[AudioQuality.EXHIGH])
        assertEquals("123", enriched.catalogId)
        assertEquals("45", enriched.mediaId)
    }

    @Test fun missingTargetDoesNotReuseAnotherTiersHash() {
        val error = runCatching { JSONObject("""{"data":[{"quality":"128","hash":"base"}]}""")
            .withKugouPlaybackResource(track(), AudioQuality.LOSSLESS) }.exceptionOrNull()
        assertEquals(KugouPlaybackFailureReason.RESOURCE_MISSING, (error as KugouPlaybackFailure).reason)
    }

    @Test fun rejectedUrlIsDifferentFromEmptyServerResponse() {
        assertEquals(KugouPlaybackFailureReason.URL_REJECTED,
            (kugouMissingPlaybackUrl(JSONObject("""{"url":["http://cdn.example.com/a.mp3"]}"""), "无地址")
                as KugouPlaybackFailure).reason)
        assertEquals(KugouPlaybackFailureReason.SERVER_NO_URL,
            (kugouMissingPlaybackUrl(JSONObject("""{"status":1}"""), "无地址") as KugouPlaybackFailure).reason)
    }

    @Test fun onlyExplicitUnavailabilityAllowsSkipping() {
        assertTrue(kugouMaySkipFailedTrack(TrackUnavailableException("无版权")))
        assertFalse(kugouMaySkipFailedTrack(java.io.IOException("网络失败")))
        val blocked = kugouPlaybackApiError(JSONObject("""{"status":0,"error_code":20028}"""), "失败")
        assertTrue(blocked.stopsPlaybackFallback())
        assertFalse(kugouMaySkipFailedTrack(blocked))
        assertFalse(kugouMaySkipFailedTrack(kugouMissingPlaybackUrl(JSONObject(), "无地址")))
    }

    @Test fun statusTwoWithoutBusinessCodeDoesNotBlameLoginOrStopFallback() {
        val error = kugouPlaybackApiError(JSONObject("""{"status":2,"error_code":0}"""), "取址失败")
        assertTrue(error is KugouPlaybackFailure)
        assertEquals("取址失败", error.message)
        assertFalse(error.stopsPlaybackFallback())
        assertFalse(kugouMaySkipFailedTrack(error))
    }

    @Test fun explicitAuthenticationErrorStillStopsFallback() {
        val error = kugouPlaybackApiError(JSONObject("""{"status":2,"error_code":401}"""), "取址失败")
        assertTrue(error is KugouPlaybackBlocked)
        assertTrue(error.stopsPlaybackFallback())
    }

    @Test fun emptyFallbackPreservesSpecificFailureAndAuthenticationStillWins() {
        val primary = KugouPlaybackFailure(KugouPlaybackFailureReason.URL_REJECTED, "地址不兼容")
        val empty = kugouPlaybackApiError(JSONObject("""{"status":2}"""), "没有地址")
        assertSame(primary, kugouPreferredPlaybackFailure(primary, empty))
        val blocked = kugouPlaybackApiError(JSONObject("""{"error_code":20028}"""), "安全验证")
        assertSame(blocked, kugouPreferredPlaybackFailure(primary, blocked))
    }
}
