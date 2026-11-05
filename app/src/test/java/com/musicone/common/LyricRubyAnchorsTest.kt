package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricRubyAnchorsTest {
    @Test fun anchoredRubyWorksWithoutWords() {
        val line = TimedLyric(1000, "今日は晴れ", transliteration = "{今日|きょう}は{晴|は}れ")
        val groups = lyricRubyGroups(line, lyricWordHighlightRanges(line))!!
        assertEquals(listOf("今日", "晴"), groups.map { line.text.substring(it.textStart, it.textEnd) })
        assertEquals(listOf("きょう"), groups[0].moras)
        // 没有逐字时整组视为已亮，随整行透明度显示。
        assertEquals(1f, groups[0].progress { 0f }, .001f)
        assertEquals(LyricRowContent.RUBY, lyricRowContent(line))
    }

    @Test fun anchoredRubyFollowsOverlappingWords() {
        val words = listOf(LyricWord(1000, 1200, "今"), LyricWord(1200, 1600, "日"), LyricWord(1600, 1800, "は"))
        val line = TimedLyric(1000, "今日は", words = words, transliteration = "{今日|kyo u}は")
        val ranges = lyricWordHighlightRanges(line)
        val group = lyricRubyGroups(line, ranges)!!.single()
        assertEquals(listOf("kyo", "u"), group.moras)
        assertEquals(2, group.words.size)
        // 今 占 200ms、日 占 400ms，只点亮“今”时进度为三分之一。
        assertEquals(1f / 3f, group.progress { if (it == group.words[0].rangeIndex) 1f else 0f }, .001f)
    }

    @Test fun mismatchedTextShowsNoRuby() {
        listOf("{今天|きょう}は晴れ", "{今日|}は晴れ", "{今日|きょう は晴れ", "今日は晴れ").forEach {
            assertNull(it, lyricRubyAnchors("今日は晴れ", it))
            assertTrue(it, !lyricHasRuby(TimedLyric(0, "今日は晴れ", transliteration = it)))
        }
    }

    @Test fun wordFormatConvertsToAnchoredText() {
        val words = listOf(LyricWord(1000, 1400, "夜"), LyricWord(1400, 1600, "に"))
        val line = TimedLyric(1000, "夜に", words = words, transliteration = "yo ru\tni")
        assertEquals("{夜|yo ru}{に|ni}", lyricRubyAnchoredText(line))
        assertEquals("{夜|よる}に", lyricRubyAnchoredText(line.copy(transliteration = "{夜|よる}に")))
    }

    @Test fun koreanWrapKeepsAnchoredRubyAligned() {
        val line = TimedLyric(0, "사랑 해", transliteration = "{사랑|sa rang} {해|hae}")
        val wrapped = lyricKeepKoreanWords(line)
        val anchors = lyricRubyAnchors(wrapped.text, wrapped.transliteration)
        assertNotNull(anchors)
        assertEquals("sa rang", anchors!![0].reading)
        assertEquals("사랑", wrapped.text.substring(anchors[0].start, anchors[0].end).replace("⁠", ""))
    }

    @Test fun localLyricsReadInlineAndSeparateRubyLines() {
        val parsed = parseLibraryLyrics("""
            [00:01.00]{今日|きょう}は晴れ
            [00:01.00]今天天气晴朗
            [00:03.00]夜に
            [00:03.00]{夜|よる}に
            [00:05.00]夜に
            [00:05.00]夜晚
        """.trimIndent())
        assertEquals(listOf("今日は晴れ", "夜に", "夜に"), parsed.map { it.text })
        assertEquals("{今日|きょう}は晴れ", parsed[0].transliteration)
        assertEquals("今天天气晴朗", parsed[0].translation)
        // 去掉标注后与原文相同的同时刻行是注音，不算翻译。
        assertEquals("{夜|よる}に", parsed[1].transliteration)
        assertNull(parsed[1].translation)
        assertNull(parsed[2].transliteration)
        assertEquals("夜晚", parsed[2].translation)
    }

    @Test fun localWordTimedLineStripsMarkupFromWords() {
        val parsed = parseLibraryLyrics("[00:01.00]<00:01.00>{今日|きょう}<00:01.50>は<00:02.00>").single()
        assertEquals("今日は", parsed.text)
        assertEquals(listOf("今日", "は"), parsed.words.map { it.text })
        assertEquals(1, lyricRubyGroups(parsed, lyricWordHighlightRanges(parsed))!!.size)
    }
}
