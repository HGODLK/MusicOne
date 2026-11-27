package com.musicone

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricRubyTest {
    private val words = listOf(LyricWord(1000, 1600, "心"), LyricWord(1600, 1800, "の"), LyricWord(1800, 2000, " "),
        LyricWord(2000, 2200, "今"), LyricWord(2200, 2400, "日"))
    private fun line(transliteration: String?) = TimedLyric(1000, "心の 今日", words = words, transliteration = transliteration)

    @Test fun parsesGroupsAndMergesWords() {
        val value = line("ko@0+300 ko@300+100 ro@400+200\tno\t\tkyo u\t+")
        val groups = lyricRubyGroups(value, lyricWordHighlightRanges(value))!!
        assertEquals(3, groups.size)
        // 逐音时间只取读音，点亮跟字组进度走。
        assertEquals(listOf("ko", "ko", "ro"), groups[0].moras)
        // 今日 共用读音，范围覆盖两个字，进度按两词时长加权。
        assertEquals("今日", value.text.substring(groups[2].textStart, groups[2].textEnd))
        assertEquals(2, groups[2].words.size)
        assertEquals(.5f, groups[2].progress { if (it == groups[2].words[0].rangeIndex) 1f else 0f }, .001f)
    }

    @Test fun groupSweepMovesAcrossWholeGroup() {
        // 中途左侧已亮、右侧未亮，开始和结束时整组亮度一致，这时可以直接画位图。
        assertTrue(lyricWordSweepAlpha(.5f, 0f, 1f, true) > lyricWordSweepAlpha(.5f, 1f, 1f, true))
        assertEquals(lyricWordSweepAlpha(0f, 0f, 1f, true), lyricWordSweepAlpha(0f, 1f, 1f, true), 0f)
        assertEquals(lyricWordSweepAlpha(1f, 0f, 1f, true), lyricWordSweepAlpha(1f, 1f, 1f, true), 0f)
    }

    @Test fun malformedTextShowsNoRuby() {
        // 段数不符、悬空合标或普通整行罗马音都不能上屏。
        listOf("ko ko ro no", "ko\tno", "+\tno\t\t\t", "\t\t\t\t", null).forEach {
            val value = line(it)
            assertNull(it, lyricRubyGroups(value, lyricWordHighlightRanges(value)))
        }
    }

    @Test fun rowContentAndShortHeightIncludeRuby() {
        val typography = lyricTypographyForWidth(PlayerLayoutMode.COMPACT_SINGLE, 340f)
        assertEquals(LyricRowContent.RUBY, lyricRowContent(line("ko\tno\t\tkyo u\t+")))
        assertEquals(LyricRowContent.PLAIN, lyricRowContent(line(null)))
        assertEquals(typography.activeLineSp + typography.rubyLineSp, lyricShortRowLineSp(typography, LyricRowContent.RUBY), .001f)
        assertEquals(typography.activeLineSp, lyricShortRowLineSp(typography, LyricRowContent.TRANSLATED), .001f)
        assertTrue(lyricRowTranslated(LyricRowContent.RUBY_TRANSLATED))
    }

    @Test fun displayContentOnlyTrimsWhatTheSettingHides() {
        val full = listOf(line("ko\tno\t\tkyo u\t+").copy(translation = "译文"))
        assertSame(full, lyricDisplayLines(full, LyricDisplayContent.ROMAJI))
        lyricDisplayLines(full, LyricDisplayContent.TRANSLATION).single().let {
            assertEquals("译文", it.translation)
            assertNull(it.transliteration)
        }
        lyricDisplayLines(full, LyricDisplayContent.ORIGINAL).single().let {
            assertNull(it.translation)
            assertNull(it.transliteration)
            assertEquals(words, it.words)
        }
        assertEquals(LyricDisplayContent.TRANSLATION, LyricDisplayContent.fromStored(null))
    }

    @Test fun placementCentersThenBorrowsNeighbourSpaceWithoutOverlap() {
        val placed = placeLyricRubyLine(listOf(LyricRubySpan(0f, 32f, 14f), LyricRubySpan(32f, 64f, 42f), LyricRubySpan(64f, 96f, 14f)),
            96f, 11f, 2f)
        assertEquals(9f, placed[0].left, .001f)
        assertEquals(27f, placed[1].left, .001f)
        assertEquals(1f, placed[1].scale, .001f)
        assertTrue(placed[2].left >= placed[1].left + 42f + 2f)
        assertTrue(placed[2].left + 14f <= 96f)
    }

    @Test fun onlyTooWideGroupShrinksAndStaysInsideLine() {
        val placed = placeLyricRubyLine(listOf(LyricRubySpan(0f, 32f, 80f)), 200f, 11f, 2f)
        assertEquals(.75f, placed[0].scale, .001f)
        assertEquals(0f, placed[0].left, .001f)
    }

    @Test fun inkBoundsKeepOverhangingStrokesAroundTheLayout() {
        val bounds = lyricRubyInkBounds(30, 20, 14f)
        assertEquals(-3, bounds.left)
        assertEquals(-3, bounds.top)
        assertEquals(33, bounds.right)
        assertEquals(23, bounds.bottom)
        assertEquals(-1, lyricRubyInkBounds(30, 20, 1f).left)
    }

    @Test fun cacheKeepsTransliteration() {
        val decoded = decodeCachedLyrics(encodeCachedLyrics(listOf(line("ko\tno\t\tkyo u\t+"))))
        assertEquals("ko\tno\t\tkyo u\t+", decoded.single().transliteration)
    }

    @Test fun lineHorizontalBoundsStopsBeforeSoftWrapLineEnd() {
        // 模拟软折行：0..11 在第一行，11 起在第二行（x=0）；行尾词 9..11 只查询 9、10，绝不把下一行 x=0 混入。
        val text = "青春から全部奪ってったものを"
        val bounds = lyricLineHorizontalBounds(9, 11, text, lineTop = 0f, lineBottom = 40f) { offset ->
            assertTrue("不应访问行尾之外的偏移 $offset", offset in 9 until 11)
            val left = offset * 80f
            Rect(left, 0f, left + 80f, 40f)
        }
        assertEquals(Rect(720f, 0f, 880f, 40f), bounds)
    }

    @Test fun lineHorizontalBoundsIgnoresZeroWidthJoinerAndSupportsRtl() {
        val text = "한\u2060글"
        val joined = lyricLineHorizontalBounds(0, text.length, text, 10f, 50f) { offset ->
            when (offset) {
                0 -> Rect(100f, 10f, 140f, 50f)
                1 -> Rect(0f, 10f, 0f, 50f)
                else -> Rect(140f, 10f, 180f, 50f)
            }
        }
        assertEquals(Rect(100f, 10f, 180f, 50f), joined)

        val rtl = lyricLineHorizontalBounds(0, 2, "اب", 0f, 30f) { offset ->
            if (offset == 0) Rect(240f, 0f, 200f, 30f) else Rect(200f, 0f, 160f, 30f)
        }
        assertEquals(Rect(160f, 0f, 240f, 30f), rtl)
    }
}
