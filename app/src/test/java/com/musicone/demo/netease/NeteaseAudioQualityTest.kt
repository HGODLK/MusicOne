package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseAudioQualityTest {
    @Test fun realFlacHeaderOverridesLevelAndCompressedBitRate() {
        assertEquals(AudioQuality.HI_RES, neteaseAudioQuality("lossless", "flac", 930927, QqFlacInfo(1000, 96000, 24)))
        assertEquals(AudioQuality.LOSSLESS, neteaseAudioQuality("hires", "flac", 2791112, QqFlacInfo(1000, 48000, 16)))
        assertEquals(AudioQuality.LOSSLESS, neteaseAudioQuality("", "flac", 2791112))
        assertEquals(AudioQuality.HI_RES, neteaseAudioQuality("hires", "flac", 930927))
        assertEquals(AudioQuality.HI_RES, neteaseAudioQuality("LOSSLESS", "FLAC", 900000,
            QqFlacInfo(0, 96000, 24)))
    }

    @Test fun downgradedMp3DoesNotInheritRequestedHiresAndTrialRemainsTrial() {
        val value = JSONObject().put("url", "https://example.com/song.mp3").put("level", "exhigh")
            .put("type", "mp3").put("br", 320000).put("sr", 48000)
        val source = neteasePlaybackSource(value, AudioQuality.HI_RES) { fail("MP3 不应探测 FLAC"); null }
        assertEquals(AudioQuality.EXHIGH, source.actualQuality)
        assertEquals(AudioQuality.HI_RES, source.requestedQuality)
        assertFalse(source.trial)
        value.put("freeTrialInfo", JSONObject().put("start", 0).put("end", 30))
        assertTrue(neteasePlaybackSource(value, AudioQuality.HI_RES) { null }.trial)
    }

    @Test fun verifiedSpecificationsReachSourceAndEnhancedLevelsNeverBecomeDolby() {
        val value = JSONObject().put("url", "https://example.com/song.flac").put("level", "lossless")
            .put("type", "flac").put("br", 900000)
        val source = neteasePlaybackSource(value, AudioQuality.LOSSLESS) { QqFlacInfo(240000, 96000, 24) }
        assertEquals(AudioQuality.HI_RES, source.actualQuality)
        assertEquals("FLAC · 24bit / 96kHz", source.actualFormat)
        assertEquals("HiRes无损", AudioQuality.HI_RES.displayLabel(MusicSource.NETEASE))
        assertEquals("FLAC无损", AudioQuality.LOSSLESS.displayLabel(MusicSource.NETEASE))
        assertEquals("MP3高品质", AudioQuality.EXHIGH.displayLabel(MusicSource.NETEASE))
        assertEquals("MP3标准", AudioQuality.STANDARD.displayLabel(MusicSource.NETEASE))
        for (level in listOf("sky", "vivid", "jyeffect", "jymaster", "dolby")) {
            assertEquals(AudioQuality.LOSSLESS, neteaseAudioQuality(level, "flac", 3000000))
        }
    }

    @Test fun fourTiersAvailabilityForHiresTrack() = kotlinx.coroutines.runBlocking {
        val qualities = probeNeteaseAvailableQualities { quality ->
            when (quality) {
                AudioQuality.STANDARD -> PlaybackSource("https://example.com/128.mp3", quality, AudioQuality.STANDARD, 128000, "mp3", false)
                AudioQuality.EXHIGH -> PlaybackSource("https://example.com/320.mp3", quality, AudioQuality.EXHIGH, 320000, "mp3", false)
                AudioQuality.LOSSLESS -> PlaybackSource("https://example.com/song.flac", quality, AudioQuality.HI_RES, 2791112, "flac", false)
                AudioQuality.HI_RES -> PlaybackSource("https://example.com/song.flac", quality, AudioQuality.HI_RES, 2791112, "flac", false)
                else -> PlaybackSource("https://example.com/other", quality, AudioQuality.STANDARD, 128000, "mp3", false)
            }
        }
        assertEquals(listOf(AudioQuality.STANDARD, AudioQuality.EXHIGH, AudioQuality.LOSSLESS, AudioQuality.HI_RES), qualities)
        val visible = visiblePlayerQualities(qualities)
        assertEquals(listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD), visible)
        assertEquals(listOf("HiRes", "FLAC", "MP3H", "MP3L"), visible.map { it.displayCompactLabel(MusicSource.NETEASE) })
    }

    @Test fun preferredLosslessDisplaysAsFlacWhenHiresSuperFulfilled() {
        assertEquals(AudioQuality.LOSSLESS, playerDisplayedQuality(AudioQuality.LOSSLESS, AudioQuality.HI_RES))
        assertEquals(AudioQuality.HI_RES, playerDisplayedQuality(AudioQuality.HI_RES, AudioQuality.HI_RES))
        assertEquals(AudioQuality.EXHIGH, playerDisplayedQuality(AudioQuality.EXHIGH, AudioQuality.EXHIGH))
        assertEquals(AudioQuality.STANDARD, playerDisplayedQuality(AudioQuality.STANDARD, AudioQuality.STANDARD))
        assertEquals(listOf(AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD),
            AudioQuality.LOSSLESS.fallbackCandidates(MusicSource.NETEASE))
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
        assertEquals(128_000, neteaseQualityBitrate(AudioQuality.STANDARD))
        assertEquals(192_000, neteaseQualityBitrate(AudioQuality.HIGHER))
        assertEquals(320_000, neteaseQualityBitrate(AudioQuality.EXHIGH))
        assertEquals(350_000, neteaseQualityBitrate(AudioQuality.LOSSLESS))
        assertEquals(999_000, neteaseQualityBitrate(AudioQuality.HI_RES))
    }
}
