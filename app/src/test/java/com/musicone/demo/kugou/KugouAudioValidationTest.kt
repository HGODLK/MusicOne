package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouAudioValidationTest {
    private val track = MusicTrack(
        id = "kugou-base", source = MusicSource.KUGOU, title = "歌曲", artists = "歌手", album = "专辑",
        durationMs = 180_000, artworkStart = 0, artworkEnd = 0, artworkMark = "歌", previewUrl = "",
        qualityIds = mapOf(
            AudioQuality.STANDARD to "base",
            AudioQuality.EXHIGH to "hqhash",
            AudioQuality.LOSSLESS to "sqhash",
            AudioQuality.HI_RES to "reshash",
        ),
    )

    @Test fun returnedHashWinsOverRequestedBitrate() {
        assertEquals(
            AudioQuality.LOSSLESS,
            kugouPlaybackQuality("SQHASH", "https://cdn.kugou.com/file.flac", "flac", 320_000, track),
        )
        assertEquals(
            AudioQuality.EXHIGH,
            kugouPlaybackQuality("", "https://cdn.kugou.com/HQHASH.mp3", "mp3", 128_000, track),
        )
    }

    @Test fun formatFallbackNeverClaimsHiRes() {
        assertEquals(
            AudioQuality.LOSSLESS,
            kugouPlaybackQuality("", "https://cdn.kugou.com/file.flac", "flac", 1_500_000, track),
        )
        assertNull(kugouPlaybackQuality("", "https://cdn.kugou.com/file.bin", "", 0, track))
    }

    @Test fun durationToleranceRejectsClips() {
        assertTrue(kugouMediaDurationMatches(181_500, 180_000))
        assertFalse(kugouMediaDurationMatches(60_000, 180_000))
        assertFalse(kugouMediaDurationMatches(0, 180_000))
    }

    @Test fun bitrateAcceptsKbpsAndBpsResponses() {
        assertEquals(320_000, normalizedKugouBitrate(320))
        assertEquals(320_000, normalizedKugouBitrate(320_000))
    }

    @Test fun mediaTypeAndHiResDepthMustMatchRequestedQuality() {
        val mp3 = KugouMediaInfo(180_000, "audio/mpeg")
        val cdFlac = KugouMediaInfo(180_000, "audio/flac", 44_100, 16)
        val hiRes = KugouMediaInfo(180_000, "audio/flac", 96_000, 24)
        assertTrue(kugouMediaMatches(mp3, 180_000, AudioQuality.STANDARD))
        assertFalse(kugouMediaMatches(mp3, 180_000, AudioQuality.LOSSLESS))
        assertTrue(kugouMediaMatches(cdFlac, 180_000, AudioQuality.LOSSLESS))
        assertFalse(kugouMediaMatches(cdFlac, 180_000, AudioQuality.HI_RES))
        assertTrue(kugouMediaMatches(hiRes, 180_000, AudioQuality.HI_RES))
    }
}
