package com.musicone.library

import com.musicone.LyricWord
import com.musicone.localLyricExtension
import com.musicone.parseLibraryLyrics
import org.junit.Assert.*
import org.junit.Test

class LocalLyricParserTest {
    @Test fun lrcPairsBilingualRowsByTimestampAndKeepsOriginalWordTiming() {
        val lyrics = parseLibraryLyrics("""
            [00:02.00]你好
            [00:01.00]<00:01.00>Hello <00:01.50>world<00:01.90>
            [00:01.00]你好世界
            [00:02.00]Hello
        """.trimIndent())

        assertEquals(2, lyrics.size)
        assertEquals("Hello world", lyrics[0].text)
        assertEquals("你好世界", lyrics[0].translation)
        assertEquals(listOf(LyricWord(1000, 1500, "Hello "), LyricWord(1500, 1900, "world")), lyrics[0].words)
        assertEquals("你好", lyrics[1].text)
        assertEquals("Hello", lyrics[1].translation)
    }

    @Test fun lrcAppliesFileOffsetToLineAndWordBoundariesOnlyOnce() {
        val lyric = parseLibraryLyrics("[offset:-250]\n[01:02.34]<01:02.34>甲<01:03.000>乙<01:03.75>").single()

        assertEquals(62090L, lyric.timeMs)
        assertEquals(listOf(LyricWord(62090, 62750, "甲"), LyricWord(62750, 63500, "乙")), lyric.words)
    }

    @Test fun ordinaryLrcKeepsRepeatedLineStampsAndLiteralBracketText() {
        val lyrics = parseLibraryLyrics("[00:01.2][00:03.45]副歌[重复]\n[00:05]下一句")

        assertEquals(listOf(1200L, 3450L, 5000L), lyrics.map { it.timeMs })
        assertEquals(listOf("副歌[重复]", "副歌[重复]", "下一句"), lyrics.map { it.text })
        assertTrue(lyrics.all { it.words.isEmpty() && it.translation == null })
    }

    @Test fun xlrcAssociatesLanguageRowsAndRemovesVoiceAndReadingMarkers() {
        val lyrics = parseLibraryLyrics("""
            [00:01.000][v:lead]<00:01.000>君[きみ]<00:01.500>へ<00:02.000>
            [>zh-Hans][v:lead]献给你
            [>en]To you
            [00:03.000]次の歌
        """.trimIndent(), "xlrc")

        assertEquals("君へ", lyrics[0].text)
        assertEquals("献给你\nTo you", lyrics[0].translation)
        assertEquals(listOf(LyricWord(1000, 1500, "君"), LyricWord(1500, 2000, "へ")), lyrics[0].words)
        assertNull(lyrics[1].translation)
    }

    @Test fun xlrcKeepsSimultaneousVoiceRowsSeparate() {
        val lyrics = parseLibraryLyrics("[00:01][v:1]甲\n[>en]A\n[00:01][v:2]乙\n[>en]B", "xlrc")

        assertEquals(listOf("甲", "乙"), lyrics.map { it.text })
        assertEquals(listOf("A", "B"), lyrics.map { it.translation })
    }

    @Test fun splSupportsSquareWordStampsAndStopsTranslationAtMetadata() {
        val lyrics = parseLibraryLyrics("""
            [00:01.000]Hello[00:01.500] world[00:02.000]
            你好世界
            [ar:歌手]
            这不是译文
            [00:03.000]Next
        """.trimIndent(), "spl")

        assertEquals("Hello world", lyrics[0].text)
        assertEquals("你好世界", lyrics[0].translation)
        assertEquals(listOf(LyricWord(1000, 1500, "Hello"), LyricWord(1500, 2000, " world")), lyrics[0].words)
        assertNull(lyrics[1].translation)
    }

    @Test fun extensionSyntaxDoesNotChangeOrdinaryLrcText() {
        val lyrics = parseLibraryLyrics("[00:01]君[きみ]\n[>en]not an LRC translation\n无时间戳行")

        assertEquals("君[きみ]", lyrics.single().text)
        assertNull(lyrics.single().translation)
        assertEquals("xlrc", localLyricExtension("歌曲.XLRC"))
        assertEquals("spl", localLyricExtension("歌曲.spl"))
        assertEquals("lrc", localLyricExtension("未识别文件.txt"))
    }
}
