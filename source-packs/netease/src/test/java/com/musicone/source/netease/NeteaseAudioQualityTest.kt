package com.musicone.source.netease

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseAudioQualityTest {
    @Test fun realFlacHeaderOverridesLevelAndCompressedBitRate() {
        assertEquals(NeteaseQuality.HI_RES, neteaseAudioQuality("lossless", "flac", 930927, NeteaseFlacInfo(1000, 96000, 24)))
        assertEquals(NeteaseQuality.LOSSLESS, neteaseAudioQuality("hires", "flac", 2791112, NeteaseFlacInfo(1000, 48000, 16)))
        assertEquals(NeteaseQuality.LOSSLESS, neteaseAudioQuality("", "flac", 2791112))
        assertEquals(NeteaseQuality.HI_RES, neteaseAudioQuality("hires", "flac", 930927))
        assertEquals(NeteaseQuality.HI_RES, neteaseAudioQuality("LOSSLESS", "FLAC", 900000,
            NeteaseFlacInfo(0, 96000, 24)))
    }


    @Test fun downgradedMp3DoesNotInheritRequestedHiresAndTrialRemainsTrial() {
        val value = JSONObject().put("url", "https://example.com/song.mp3").put("level", "exhigh")
            .put("type", "mp3").put("br", 320000).put("sr", 48000)
        val source = neteasePlaybackSource(value, NeteaseQuality.HI_RES) { fail("MP3 不应探测 FLAC"); null }
        assertEquals(NeteaseQuality.EXHIGH, source.actualQuality)
        assertEquals(NeteaseQuality.HI_RES, source.requestedQuality)
        assertFalse(source.trial)
        value.put("freeTrialInfo", JSONObject().put("start", 0).put("end", 30))
        assertTrue(neteasePlaybackSource(value, NeteaseQuality.HI_RES) { null }.trial)
    }


    @Test fun verifiedSpecificationsReachSourceAndEnhancedLevelsNeverBecomeDolby() {
        val value = JSONObject().put("url", "https://example.com/song.flac").put("level", "lossless")
            .put("type", "flac").put("br", 900000)
        val source = neteasePlaybackSource(value, NeteaseQuality.LOSSLESS) { NeteaseFlacInfo(240000, 96000, 24) }
        assertEquals(NeteaseQuality.HI_RES, source.actualQuality)
        assertEquals("FLAC · 24bit / 96kHz", source.actualFormat)
        for (level in listOf("sky", "vivid", "jyeffect", "jymaster", "dolby")) {
            assertEquals(NeteaseQuality.LOSSLESS, neteaseAudioQuality(level, "flac", 3000000))
        }
    }


    @Test fun hiresOnlyTrackDoesNotExposeFlacTier() = kotlinx.coroutines.runBlocking {
        val qualities = NETEASE_AUDIO_QUALITIES.filter { quality ->
            verifiedNeteaseQuality(quality, when (quality) {
                NeteaseQuality.STANDARD -> NeteasePlaybackAddress("https://example.com/128.mp3", quality, NeteaseQuality.STANDARD, 128000, "mp3", false)
                NeteaseQuality.EXHIGH -> NeteasePlaybackAddress("https://example.com/320.mp3", quality, NeteaseQuality.EXHIGH, 320000, "mp3", false)
                NeteaseQuality.LOSSLESS -> NeteasePlaybackAddress("https://example.com/song.flac", quality, NeteaseQuality.HI_RES, 2791112, "flac", false)
                NeteaseQuality.HI_RES -> NeteasePlaybackAddress("https://example.com/song.flac", quality, NeteaseQuality.HI_RES, 2791112, "flac", false)
                else -> NeteasePlaybackAddress("https://example.com/other", quality, NeteaseQuality.STANDARD, 128000, "mp3", false)
            })
        }
        // 无损请求只返回 Hi-Res 文件时，FLAC 档不能冒充可用。
        assertEquals(listOf(NeteaseQuality.STANDARD, NeteaseQuality.EXHIGH, NeteaseQuality.HI_RES), qualities)
        val sq = NeteasePlaybackAddress("https://example.com/sq.flac", NeteaseQuality.LOSSLESS, NeteaseQuality.LOSSLESS, 900000, "flac", false)
        assertTrue(verifiedNeteaseQuality(NeteaseQuality.LOSSLESS, sq))
        assertEquals(listOf("LOSSLESS", "EXHIGH", "STANDARD"), NeteaseSourceServices { error("质量策略不访问状态") }.fallbackQualityIds("LOSSLESS"))
    }

    @Test fun playbackUrlStripsInvalidAuthSecret() {
        val urlWithMiddleSecret = "http://m704.music.126.net/song.flac?vuutv=test&authSecret=000001&cdntag=test"
        assertEquals("https://m704.music.126.net/song.flac?vuutv=test&cdntag=test", urlWithMiddleSecret.asNeteasePlaybackUrl())

        val urlWithTrailingSecret = "http://m704.music.126.net/song.flac?vuutv=test&authSecret=000001"
        assertEquals("https://m704.music.126.net/song.flac?vuutv=test", urlWithTrailingSecret.asNeteasePlaybackUrl())

        val urlWithLeadingSecret = "http://m704.music.126.net/song.flac?authSecret=000001&cdntag=test"
        assertEquals("https://m704.music.126.net/song.flac?cdntag=test", urlWithLeadingSecret.asNeteasePlaybackUrl())

        val urlWithOnlySecret = "http://m704.music.126.net/song.flac?authSecret=000001"
        assertEquals("https://m704.music.126.net/song.flac", urlWithOnlySecret.asNeteasePlaybackUrl())

        val cleanUrl = "http://m704.music.126.net/song.flac?vuutv=test&cdntag=test"
        assertEquals("https://m704.music.126.net/song.flac?vuutv=test&cdntag=test", cleanUrl.asNeteasePlaybackUrl())
    }


    @Test fun qualityBitrateMapsToStandardNeteaseTiers() {
        assertEquals(128_000, neteaseQualityBitrate(NeteaseQuality.STANDARD))
        assertEquals(192_000, neteaseQualityBitrate(NeteaseQuality.HIGHER))
        assertEquals(320_000, neteaseQualityBitrate(NeteaseQuality.EXHIGH))
        assertEquals(350_000, neteaseQualityBitrate(NeteaseQuality.LOSSLESS))
        assertEquals(999_000, neteaseQualityBitrate(NeteaseQuality.HI_RES))
    }

}
