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
        val source = neteasePlaybackSource(value, AudioQuality.HI_RES) { _, _ -> fail("MP3 不应探测 FLAC"); null }
        assertEquals(AudioQuality.EXHIGH, source.actualQuality)
        assertEquals(AudioQuality.HI_RES, source.requestedQuality)
        assertFalse(source.trial)
        value.put("freeTrialInfo", JSONObject().put("start", 0).put("end", 30))
        assertTrue(neteasePlaybackSource(value, AudioQuality.HI_RES) { _, _ -> null }.trial)
    }

    @Test fun verifiedSpecificationsReachSourceAndEnhancedLevelsNeverBecomeDolby() {
        val value = JSONObject().put("url", "https://example.com/song.flac").put("level", "lossless")
            .put("type", "flac").put("br", 900000)
        val source = neteasePlaybackSource(value, AudioQuality.LOSSLESS) { _, _ -> QqFlacInfo(240000, 96000, 24) }
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

    @Test fun playbackKeepsProviderSchemeAndUsesAndroidMediaHeaders() {
        val httpUrl = "http://m7.music.126.net/song.flac?ticket=test"
        val value = JSONObject().put("url", httpUrl).put("level", "lossless").put("type", "flac")
        var inspectedUrl = ""
        var inspectedHeaders: Map<String, String> = emptyMap()

        val source = neteasePlaybackSource(value, AudioQuality.LOSSLESS) { url, headers ->
            inspectedUrl = url
            inspectedHeaders = headers
            null
        }

        assertEquals(httpUrl, source.url)
        assertEquals(httpUrl, inspectedUrl)
        assertEquals("https://music.163.com/", source.requestHeaders["Referer"])
        assertTrue(source.requestHeaders["User-Agent"].orEmpty().startsWith("NeteaseMusic/9.6.08(9006008);Dalvik/"))
        assertEquals(source.requestHeaders, inspectedHeaders)
        assertNull("file:///tmp/song.flac".asNeteasePlaybackUrl())
    }
}
