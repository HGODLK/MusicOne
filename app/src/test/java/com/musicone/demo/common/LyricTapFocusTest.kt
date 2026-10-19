package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricTapFocusTest {
    @Test fun pausedPrerollKeepsClickedFocusUntilTheRealLineStarts() {
        val focus = LyricTapFocus()
        focus.request(8, 10_000L, 9_650L)
        repeat(3) { focus.observe(9_650L) }
        assertEquals(8, focus.line)
        focus.observe(9_999L)
        assertEquals(8, focus.line)
        focus.observe(10_000L)
        assertNull(focus.line)
    }

    @Test fun newestClickReplacesThePreviousTarget() {
        val focus = LyricTapFocus()
        focus.request(20, 50_000L, 49_650L)
        focus.request(3, 5_000L, 4_650L)
        focus.observe(4_700L)
        assertEquals(3, focus.line)
        focus.observe(5_000L)
        assertNull(focus.line)
    }

    @Test fun staleFramesCannotReleaseBackwardSeekOrFlashThePreviousLine() {
        val focus = LyricTapFocus()
        focus.request(8, 10_000L, 9_650L)
        focus.observe(9_650L, 50_000L)
        assertEquals(8, focus.line)
        focus.observe(10_001L, 9_999L)
        assertEquals(8, focus.line)
        focus.observe(10_010L, 10_005L)
        assertNull(focus.line)
        focus.request(8, 10_000L, 9_650L)
        focus.observe(9_650L, 2_000L)
        assertEquals(8, focus.line)
    }

    @Test fun anotherSeekOrGestureReleasesTheClickedTarget() {
        val focus = LyricTapFocus()
        focus.request(8, 10_000L, 9_650L)
        focus.observe(2_000L)
        assertNull(focus.line)
        focus.request(8, 10_000L, 9_650L)
        focus.clear()
        assertNull(focus.line)
    }

    @Test fun onlineOffsetPreservesWordTimingAndTranslation() {
        val words = listOf(LyricWord(1000L, 1500L, "测试"))
        val line = TimedLyric(1000L, "测试", translation = "译文", words = words)
        val shifted = offsetOnlineLyricLines(listOf(line), -200L).single()
        assertEquals(800L, shifted.timeMs)
        assertSame(words, shifted.words)
        assertEquals("译文", shifted.translation)
    }

    @Test fun onlineSourcesUseTheirOwnLineLead() {
        assertEquals(10_380L, lyricDisplayPosition(10_000L, MusicSource.NETEASE))
        listOf(MusicSource.LOCAL, MusicSource.BILIBILI).forEach {
            assertEquals(10_000L, lyricDisplayPosition(10_000L, it))
        }
        assertEquals(10_275L, lyricDisplayPosition(10_000L, MusicSource.QQ))
        assertEquals(10_185L, lyricDisplayPosition(10_000L, MusicSource.KUGOU))
        assertEquals(0L, (20_000L * lyricSeekFraction(200L, 20_000L)).toLong())
    }

    @Test fun wordLeadIsIndependentFromWholeLineLead() {
        assertEquals(10_105L, lyricWordDisplayPosition(10_000L, MusicSource.QQ))
        assertEquals(10_115L, lyricWordDisplayPosition(10_000L, MusicSource.KUGOU))
        for (source in listOf(MusicSource.NETEASE, MusicSource.LOCAL, MusicSource.BILIBILI)) {
            assertEquals(10_000L, lyricWordDisplayPosition(10_000L, source))
        }
    }
}
