package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test

class MigratedHostImmersiveLyricsTest {
    @Test fun qqQrcParserHandlesUnescapedQuotesWithoutTruncation() {
        val qrcXml = "<Lyric_1 Type=\"1\" LyricContent=\"[0,1000]开始(0,500)前奏(500,500)&#10;[22305,3200]\"你在哪里 往何处去\"(22305,1600)歌词(23905,1600)&#10;[26000,2000]第三句(26000,2000)&#10;[29000,1500]尾声(29000,1500)\"/>"
        val parsed = parseQqQrc(qrcXml)

        assertEquals(4, parsed.size)
        assertEquals(listOf(0L, 22_305L, 26_000L, 29_000L), parsed.map { it.timeMs })
        assertEquals("\"你在哪里 往何处去\"歌词", parsed[1].text)
        assertEquals(2, parsed[1].words.size)
        assertEquals(22_305L, parsed[1].words[0].startMs)
        assertEquals(23_905L, parsed[1].words[0].endMs)
        assertEquals("\"你在哪里 往何处去\"", parsed[1].words[0].text)
    }

}
