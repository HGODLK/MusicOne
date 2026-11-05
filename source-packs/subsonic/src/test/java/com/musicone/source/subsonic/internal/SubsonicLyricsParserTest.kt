package com.musicone.source.subsonic.internal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubsonicLyricsParserTest {
    @Test fun parsesPlainLrcWithOffsetAndRepeatedTimestamps() {
        val lines = parseLrc("""
            [ti:Song]
            [offset:+500]
            [00:12.50][01:02.00]Chorus
            [00:05.3]Intro
            plain text without time
        """.trimIndent())!!
        assertEquals(listOf(4_800L, 12_000L, 61_500L), lines.map { it.startMs })
        assertEquals(listOf("Intro", "Chorus", "Chorus"), lines.map { it.text })
    }

    @Test fun mergesSameTimestampLinesAsTranslation() {
        val lines = parseLrc("[00:01.00]こんにちは\n[00:01.00]你好\n[00:03.00]Next")!!
        assertEquals(2, lines.size)
        assertEquals("こんにちは", lines[0].text)
        assertEquals("你好", lines[0].translation)
        assertNull(lines[1].translation)
    }

    @Test fun parsesEnhancedWordTimings() {
        val line = parseLrc("[00:10.00]<00:10.00>Hel<00:10.40>lo <00:10.80>world<00:11.50>\n[00:13.00]End")!!.first()
        assertEquals("Hello world", line.text)
        assertEquals(listOf("Hel", "lo ", "world"), line.words.map { it.text })
        assertEquals(listOf(10_000L, 10_400L, 10_800L), line.words.map { it.startMs })
        assertEquals(listOf(400L, 400L, 700L), line.words.map { it.durationMs })
        assertEquals(line.text, line.words.joinToString("") { it.text })
    }

    @Test fun closesOpenLastWordAtNextLine() {
        val line = parseLrc("[00:01.00]<00:01.00>A<00:01.50>B\n[00:02.20]C")!!.first()
        assertEquals(listOf(500L, 700L), line.words.map { it.durationMs })
    }

    @Test fun returnsNullWithoutTimestamps() {
        assertNull(parseLrc("just words\nno timing"))
        assertNull(parseLrc(""))
    }

    @Test fun readsStructuredLyricsWithOffsetAndSecondLanguage() {
        val response = JSONObject("""{"lyricsList":{"structuredLyrics":[
            {"lang":"jpn","synced":true,"offset":100,"line":[{"start":1100,"value":"一行目"},{"start":3100,"value":"二行目"},{"start":4000,"value":"  "}]},
            {"lang":"chi","synced":true,"offset":100,"line":[{"start":1100,"value":"第一行"}]}
        ]}}""")
        val lines = parseStructuredLyrics(response)!!
        assertEquals(listOf(1_000L, 3_000L), lines.map { it.startMs })
        assertEquals("第一行", lines[0].translation)
        assertNull(lines[1].translation)
    }

    @Test fun unsyncedStructuredLyricsOnlyUsedWhenTheyContainLrc() {
        val plain = JSONObject("""{"lyricsList":{"structuredLyrics":[{"synced":false,"line":[{"value":"no time"}]}]}}""")
        assertNull(parseStructuredLyrics(plain))
        val lrc = JSONObject("""{"lyricsList":{"structuredLyrics":[{"synced":false,"line":[{"value":"[00:02.00]Timed"}]}]}}""")
        assertEquals(2_000L, parseStructuredLyrics(lrc)!!.single().startMs)
        assertNull(parseStructuredLyrics(JSONObject("""{"lyricsList":{}}""")))
    }
}
