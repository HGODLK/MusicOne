package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class HyperOsIslandFrameTest {
    private fun snapshot() = SystemPlaybackPresentation(
        SystemPlaybackKey("opaque:资源/甲", 4), "歌名", "歌手", null,
        listOf(SystemLyricLine(1_000, "原文一", "译文一"), SystemLyricLine(2_000, "原文二", "译文二")),
        900, 10_000, 30_000, true,
    )

    @Test fun missingAndAllBlankLyricsUseExplicitFallbackWithoutOrphanTranslation() {
        for (lyrics in listOf(emptyList(), listOf(SystemLyricLine(0, " \n", "只有译文")))) {
            val value = snapshot().copy(lyrics = lyrics)
            val content = value.hyperOsContentAt(12_000)
            assertEquals("暂无歌词", content.lyric)
            assertNull(content.translation)
            assertEquals("歌名", value.contentAt(12_000).lyric)
        }
    }

    @Test fun validFutureLyricsKeepIntroMetadataAndThenSwitchOnContinuousTime() {
        val value = snapshot()
        assertEquals("歌名", value.hyperOsContentAt(10_000).lyric)
        assertEquals("原文一", value.hyperOsContentAt(10_100).lyric)
        assertNull(value.hyperOsContentAt(10_100).translation)
        assertEquals("原文二", value.hyperOsContentAt(11_100).lyric)
        assertNull(value.hyperOsContentAt(11_100).translation)
        assertEquals("译文二", value.contentAt(11_100).translation)
    }

    @Test fun pauseAndSeekKeepTheSameClockAsThePlayer() {
        val value = snapshot().copy(positionMs = 2_500)
        assertEquals("原文二", value.hyperOsContentAt(10_000).lyric)
        assertEquals("原文一", value.copy(positionMs = 1_200).hyperOsContentAt(10_000).lyric)
        assertEquals("原文一", value.copy(positionMs = 1_200, advancing = false)
            .hyperOsContentAt(100_000).lyric)
    }

    @Test fun elapsedAndProgressUpdateWithoutExpandedLyricOrTranslation() {
        val value = HyperOsIslandFrame(snapshot().key, HyperOsIslandSummary("歌", "", "相同摘要"),
            "完整歌名", "完整歌手", 1, 30)
        assertNotEquals(value, value.copy(summary = value.summary.copy(lyric = "原文二")))
        assertNotEquals(value, value.copy(elapsedSeconds = 2))
        assertEquals(33, value.progress)
        assertEquals("00:01", value.elapsedText)
        assertEquals("61:01", value.copy(elapsedSeconds = 3_661).elapsedText)
        assertEquals(0, value.copy(durationSeconds = 0).progress)
        assertEquals(1000, value.copy(elapsedSeconds = 60).progress)
    }
}
