package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class QqDolbyPlaybackTest {
    @Test fun currentAndAjocDolbyPrefixesAreRecognizedWithoutAcceptingEncryptedFiles() {
        for (prefix in listOf("D008", "D009", "D004")) {
            assertEquals(AudioQuality.DOLBY, qqPlaybackQuality("https://example.com/${prefix}media.mp4?signature=example"))
        }
        assertNull(qqPlaybackQuality("D0M8media.mmp4"))
        assertNull(qqPlaybackQuality("D008media.flac"))
        assertEquals(AudioQuality.STANDARD, qqPlaybackQuality("M500media.mp3?file=D008media.mp4"))
    }

    @Test fun dolbyAppearsOnlyAfterAConfirmedProbe() = runBlocking {
        assertFalse(AudioQuality.DOLBY in visiblePlayerQualities(listOf(AudioQuality.LOSSLESS)))
        val unavailable = probeQqAvailableQualities(listOf(AudioQuality.DOLBY)) { throw PlatformApiException("无票据") }
        assertTrue(visiblePlayerQualities(unavailable).isEmpty())
        val verified = probeQqAvailableQualities(listOf(AudioQuality.DOLBY)) { source("D008") }
        assertEquals(listOf(AudioQuality.DOLBY), visiblePlayerQualities(verified))
    }

    @Test fun prefersCurrentSpecificationAndStopsAfterSuccess() = runBlocking {
        val tried = mutableListOf<String>()
        val result = resolveQqPlaybackVariant(AudioQuality.DOLBY) { spec ->
            tried += spec.prefix
            source(spec.prefix)
        }
        assertEquals(listOf("D008"), tried)
        assertEquals(AudioQuality.DOLBY, qqPlaybackQuality(result.url))
    }

    @Test fun triesAjocThenLegacyOnlyWhenPreviousSpecificationFails() = runBlocking {
        val tried = mutableListOf<String>()
        val result = resolveQqPlaybackVariant(AudioQuality.DOLBY) { spec ->
            tried += spec.prefix
            if (spec.prefix != "D004") throw PlatformApiException("规格不可用")
            source(spec.prefix)
        }
        assertEquals(listOf("D008", "D009", "D004"), tried)
        assertTrue(result.url.contains("D004"))
    }

    @Test fun expiredCredentialsAndCancellationNeverTryAnotherPrefix() = runBlocking {
        for (failure in listOf(QqCredentialExpiredException(104401, "测试"), CancellationException("取消"))) {
            val tried = mutableListOf<String>()
            val actual = runCatching {
                resolveQqPlaybackVariant(AudioQuality.DOLBY) { spec -> tried += spec.prefix; throw failure }
            }.exceptionOrNull()
            assertSame(failure, actual)
            assertEquals(listOf("D008"), tried)
        }
    }

    @Test fun dedicatedDolbyMidSurvivesSnapshotAndDoesNotReplaceNormalMediaMid() {
        val track = JSONObject().put("mid", "001YQRn31pGAcv").put("name", "测试歌曲")
            .put("interval", 240).put("file", JSONObject().put("media_mid", "normal-mid")
                .put("size_flac", 100).put("dolby_ajoc_448_media_mid", "dolby-mid"))
            .toQqTrack()
        assertEquals("normal-mid", track.qqPlaybackMediaMid(AudioQuality.LOSSLESS))
        assertEquals("dolby-mid", track.qqPlaybackMediaMid(AudioQuality.DOLBY))
        val restored = checkNotNull(track.toQqStoredTrackJson().toQqStoredTrack())
        assertEquals("dolby-mid", restored.qqPlaybackMediaMid(AudioQuality.DOLBY))
        val cache = QqPlaybackSourceCache { 0L }
        cache.remember(track, "test-session", source("D008"))
        assertNotNull(cache.get(track, "test-session", AudioQuality.DOLBY))
        assertNull(cache.get(track.copy(qualityIds = mapOf(AudioQuality.DOLBY to "updated-dolby-mid")),
            "test-session", AudioQuality.DOLBY))
    }

    private fun source(prefix: String) = PlaybackSource(
        "https://example.com/${prefix}media.mp4", AudioQuality.DOLBY, AudioQuality.DOLBY,
        AudioQuality.DOLBY.bitRate, "mp4", false,
    )
}
