package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class SystemPlaybackPresentationTest {
    private fun snapshot(advancing: Boolean = true, positionMs: Long = 900L, durationMs: Long = 30_000L) =
        SystemPlaybackPresentation(SystemPlaybackKey("opaque:资源/甲", 4L), "歌名", "歌手", "file:///cover.jpg",
            listOf(SystemLyricLine(1_000, "原文一", "译文一"), SystemLyricLine(2_000, "原文二", "译文二")),
            positionMs, 10_000L, durationMs, advancing)

    @Test fun lineSwitchUsesContinuousTimeInsteadOfWaitingForTheNextPositionSample() {
        val value = snapshot()
        assertEquals("歌名", value.contentAt(10_000).lyric)
        assertEquals("原文一", value.contentAt(10_100).lyric)
        assertEquals("原文二", value.contentAt(11_100).lyric)
        assertEquals(100L, value.nextUpdateDelayMs(10_000))
        assertEquals("译文一", value.contentAt(10_100).translation)
    }

    @Test fun pausedAndBufferingPlaybackDoNotAdvanceTheLyricClock() {
        val value = snapshot(advancing = false, positionMs = 1_200)
        assertEquals("原文一", value.contentAt(100_000).lyric)
        assertEquals(1_200L, value.positionAt(100_000))
    }

    @Test fun backwardSeekReturnsToTheMatchingLineAndKeepsTheActualDurationBoundary() {
        val value = snapshot(positionMs = 2_500)
        assertEquals("原文二", value.contentAt(10_000).lyric)
        assertEquals("原文一", value.copy(positionMs = 1_200).contentAt(10_000).lyric)
        assertEquals(30_000L, value.positionAt(100_000))
        assertEquals(100, value.contentAt(100_000).progress)
    }

    @Test fun missingOrBlankLyricsUseMetadataWithoutInventingATranslation() {
        val value = snapshot(positionMs = 1_200)
        assertEquals("歌名", value.copy(lyrics = emptyList()).contentAt(10_000).lyric)
        val blank = value.copy(lyrics = listOf(SystemLyricLine(0, " ", "不应单独显示"))).contentAt(10_000)
        assertEquals("歌名", blank.lyric)
        assertNull(blank.translation)
        assertNull(value.copy(lyrics = listOf(SystemLyricLine(0, "同文", "同文"))).contentAt(10_000).translation)
    }

    @Test fun unknownDurationDoesNotBecomeAFakeProgressBar() {
        assertNull(snapshot(durationMs = 0).contentAt(10_000).progress)
    }

    @Test fun artworkFromAnEarlierPlayOrDifferentUriCannotMatchTheCurrentRequest() {
        val key = snapshot().key
        val cover = SystemArtworkKey(key, "file:///cover.jpg")
        assertNotEquals(cover, SystemArtworkKey(key.copy(generation = 5), cover.uri))
        assertNotEquals(cover, SystemArtworkKey(key.copy(trackId = "another"), cover.uri))
        assertNotEquals(cover, SystemArtworkKey(key, "file:///updated.jpg"))
    }

    @Test fun everySourceCanSupplyLyricsWithoutANumericIdOrASeparateLyricRequest() {
        MusicSource.entries.forEach { source ->
            val track = MusicTrack("opaque:$source/甲", source, "歌名", "歌手", "", 30_000, 0, 0, "", "",
                lyrics = listOf(TimedLyric(1_000, "已有原文", "已有译文")))
            val line = systemLyricLinesForPlayback(track, 0L).single()
            assertEquals("已有原文", line.text)
            assertEquals("已有译文", line.translation)
        }
    }

    @Test fun upstreamTimingCalibrationPreservesOnlineOffsetsAndDoesNotOffsetLocalLyricsTwice() {
        val track = MusicTrack("opaque", MusicSource.QQ, "歌名", "歌手", "", 30_000, 0, 0, "", "",
            lyrics = listOf(TimedLyric(1_000, "原文")))
        assertEquals(975L, systemLyricLinesForPlayback(track, 250L).single().timeMs)
        assertEquals(1_000L, systemLyricLinesForPlayback(track.copy(source = MusicSource.LOCAL), 250L).single().timeMs)
        assertEquals(1_000L, systemLyricLinesForPlayback(track.copy(source = MusicSource.BILIBILI), 250L).single().timeMs)
    }
}
