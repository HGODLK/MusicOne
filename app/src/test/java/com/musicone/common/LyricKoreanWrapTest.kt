package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricKoreanWrapTest {
    private val joiner = "⁠"
    private fun plain(value: String) = value.replace(joiner, "")

    @Test fun 没有韩文时原样返回() {
        val line = TimedLyric(0, "心の 今日 hello", words = listOf(LyricWord(0, 100, "心の")))
        assertSame(line, lyricKeepKoreanWords(line))
    }

    @Test fun 只在词内加字符且空格和标点两侧不加() {
        val line = TimedLyric(0, "수는 없을까? (IF)")
        assertEquals("수${joiner}는 없${joiner}을${joiner}까? (IF)", lyricKeepKoreanWords(line).text)
    }

    @Test fun 韩文和相邻的拉丁字母数字也不断开() {
        assertEquals("3${joiner}시 Love${joiner}는", lyricKeepKoreanWords(TimedLyric(0, "3시 Love는")).text)
    }

    @Test fun 词和正文对得上且去掉字符后还原() {
        val line = TimedLyric(0, "없을까 우리", words = listOf(LyricWord(0, 100, "없"),
            LyricWord(100, 200, "을까 "), LyricWord(200, 300, "우리")))
        val shown = lyricKeepKoreanWords(line)
        assertEquals(line.text, plain(shown.text))
        assertEquals(line.words.map { it.text }, shown.words.map { plain(it.text) })
        assertEquals(line.words.map { it.startMs }, shown.words.map { it.startMs })
        val ranges = lyricWordRanges(shown)
        assertEquals(3, ranges.size)
        assertEquals(shown.text.length, ranges.last().end)
        ranges.zipWithNext { left, right -> assertEquals(left.end, right.start) }
    }

    @Test fun 高亮范围连续覆盖正文() {
        val line = TimedLyric(0, "없을까 우리", words = listOf(LyricWord(0, 100, "없을까 "), LyricWord(100, 200, "우리")))
        val ranges = lyricWordHighlightRanges(lyricKeepKoreanWords(line))
        assertEquals(2, ranges.size)
        assertTrue(ranges.all { it.word != null })
    }

    @Test fun 注音字组覆盖整个词() {
        val line = TimedLyric(0, "수는 없을까", words = listOf(LyricWord(0, 100, "수는 "), LyricWord(100, 200, "없을까")),
            transliteration = "sureun\teobseulkka")
        val shown = lyricKeepKoreanWords(line)
        val groups = lyricRubyGroups(shown, lyricWordHighlightRanges(shown))!!
        assertEquals(listOf("수는", "없을까"), groups.map { plain(shown.text.substring(it.textStart, it.textEnd)) })
        assertTrue(groups.all { group -> group.words.all { it.rangeIndex >= 0 } })
    }
}
