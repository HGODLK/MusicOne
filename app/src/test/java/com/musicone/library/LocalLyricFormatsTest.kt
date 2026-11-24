package com.musicone.library

import com.musicone.LyricWord
import com.musicone.parseLibraryLyrics
import com.musicone.parseLocalLyricBytes
import java.io.File
import java.nio.charset.Charset
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class LocalLyricFormatsTest {
    private val expected = listOf(LyricWord(1000, 1450, "First"), LyricWord(1450, 2000, " second"))
    private val qrc = "[1000,1000]First(1000,450) second(1450,550)"
    private val krc = "[1000,1000]<0,450,0>First<450,550,0> second"
    private val qrcHex = "d6a2be95d64473726115e96576868eff66ebbf416261f6731e355a94074aee5b7900758f07c6d2c0d6d28de16cd229d4"

    @Test fun detectsSquareLrcAndKeepsLiteralBrackets() {
        val lines = parseLibraryLyrics("[00:01.000]First[00:01.450] second[00:02.000]\nTranslation\n[00:04]Next [chorus]")
        assertEquals(expected, lines.first().words)
        assertEquals("Translation", lines.first().translation)
        assertEquals("Next [chorus]", lines.last().text)
    }

    @Test fun xlrcVoiceAndRubyDoNotPolluteWordText() {
        val line = parseLibraryLyrics("[00:01.000][v:lead]<00:01.000>今日[きょう]<00:01.450>は<00:02.000>\n[>en]Today").single()
        assertEquals("今日は", line.text)
        assertEquals("{今日|きょう}は", line.transliteration)
        assertEquals("Today", line.translation)
        assertEquals(listOf(LyricWord(1000, 1450, "今日"), LyricWord(1450, 2000, "は")), line.words)
    }

    @Test fun detectsPlatformFormatsByStructureAndPreservesWordTime() {
        val samples = listOf(qrc, krc, "[1000,1000](1000,450,0)First(1450,550,0) second", "[4]First(1000,450) second(1450,550)",
            """<?xml version="1.0"?><QrcInfos><Lyric_1 LyricContent="[1000,1000]First(1000,450) second(1450,550)&#10;"/></QrcInfos>""")
        for (sample in samples) {
            val row = parseLibraryLyrics(sample).single()
            assertEquals(sample, "First second", row.text)
            assertEquals(sample, expected, row.words)
        }
    }

    @Test fun krcUsesRelativeTimingAndKeepsItsTranslationTrack() {
        val language = kotlin.io.encoding.Base64.Default.encode("""{"content":[{"type":1,"lyricContent":[["Translation"]]}]}""".encodeToByteArray())
        val row = parseLibraryLyrics("[language:$language]\n$krc").single()
        assertEquals(expected, row.words)
        assertEquals("Translation", row.translation)
    }

    @Test fun decodesHexAndClientQrcAndZlibKrc() {
        assertEquals(expected, parseLocalLyricBytes(qrcHex.encodeToByteArray(), "qrc").single().words)
        val prefix = "9825b0ace3028368e8fc6c".hexToByteArray()
        // 此短样本只需客户端掩码的前 59 字节；正文与十六进制样本相同。
        val mask = ("c34ad6ca9067f752d8a166629f5b0900c35e95239f13117ed8923fbc90bb740e" +
            "c347743d90aa3f51d8f411849fde951dc3c609d59ffa66f9d8f0f7a090a1d6f3").hexToByteArray()
        val qrcBody = qrcHex.hexToByteArray().mapIndexed { i, b -> (b.toInt() xor mask[i + prefix.size].toInt()).toByte() }.toByteArray()
        assertEquals(expected, parseLocalLyricBytes(prefix + qrcBody, "lrc").single().words)
        val key = "404761775e3274475136312dced26e69".hexToByteArray()
        val deflater = Deflater()
        val data = ByteArray(256)
        val size = try { deflater.setInput(krc.encodeToByteArray()); deflater.finish(); deflater.deflate(data) } finally { deflater.end() }
        val krcBody = ByteArray(size) { i -> (data[i].toInt() xor key[i % key.size].toInt()).toByte() }
        assertEquals(expected, parseLocalLyricBytes("krc1".encodeToByteArray() + krcBody, "lrc").single().words)
        assertTrue(parseLocalLyricBytes(prefix + byteArrayOf(0), "qrc").isEmpty())
    }

    @Test fun fileEntryDecodesUtf16AndLegacyChineseWithoutChangingOriginalBytes() {
        val file = File.createTempFile("musicone-lyrics-", ".lrc")
        try {
            for (charset in listOf(Charsets.UTF_16, Charset.forName("GB18030"))) {
                val bytes = "[00:01]歌词内容".toByteArray(charset)
                file.writeBytes(bytes)
                assertEquals("歌词内容", parseLibraryLyrics(file).single().text)
                assertArrayEquals(bytes, file.readBytes())
            }
        } finally { file.delete() }
    }

    @Test fun lqeUsesItsOriginalSectionAndAlignsOtherSections() {
        val raw = """
            [Lyricify Quick Export]
            [version:1.0]
            [lyrics: format@Lyricify Syllable]
            [4]First(1000,450) second(1450,550)
            [translation: format@LRC]
            [00:01.000]Translation
            [pronunciation: format@LRC, language@romaji]
            [00:01.000]Reading
        """.trimIndent()
        val row = parseLibraryLyrics(raw).single()
        assertEquals("First second", row.text)
        assertEquals(expected, row.words)
        assertEquals("Translation", row.translation)
        assertEquals("{First second|Reading}", row.transliteration)
        assertTrue(parseLibraryLyrics(raw.replace("[4]First(1000,450) second(1450,550)", "")).isEmpty())
        assertTrue(parseLibraryLyrics("[type:LyricifyLines]\n[1000,2000]First second").single().words.isEmpty())
    }

    @Test fun appleTtmlAlignsHeadTracksAndKeepsBackgroundAsALine() {
        val raw = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" xmlns:m="http://www.w3.org/ns/ttml#metadata" itunes:timing="Word">
              <head><metadata><translations><translation><text for="L1">Head translation</text></translation></translations>
                <transliterations><transliteration><text for="L1"><span begin="1.000" end="1.450">one</span><span begin="1.450" end="2.000">two</span></text></transliteration></transliterations></metadata></head>
              <body><div begin="1.000"><p begin="1.000" end="2.000" itunes:key="L1"><span begin="1.000" end="1.450">First</span><span begin="1.450" end="2.000"> second</span><span m:role="x-translation">Inline translation</span><span m:role="x-bg"><span begin="1.600" end="2.000">Background</span></span></p></div></body>
            </tt>
        """.trimIndent()
        val lines = parseLibraryLyrics(raw)
        assertEquals(listOf("First second", "Background"), lines.map { it.text })
        assertEquals(expected, lines.first().words)
        assertEquals("Head translation\nInline translation", lines.first().translation)
        assertEquals("{First|one}{ second|two}", lines.first().transliteration)
        assertEquals(1600L, lines[1].timeMs)
    }

    @Test fun ttmlSupportsRelativeTimesAndRuby() {
        val raw = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:s="http://www.w3.org/ns/ttml#styling"><body><div begin="1s">
                <p begin="1s" dur="1s"><span begin="0ms" dur="450ms" s:ruby="container"><span s:ruby="base">君</span><span s:ruby="text">きみ</span></span><span begin="450ms" end="1s">へ</span></p>
            </div></body></tt>
        """.trimIndent()
        val row = parseLibraryLyrics(raw).single()
        assertEquals("君へ", row.text)
        assertEquals("{君|きみ}へ", row.transliteration)
        assertEquals(listOf(LyricWord(2000, 2450, "君"), LyricWord(2450, 3000, "へ")), row.words)
        assertTrue(parseLibraryLyrics("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///private'>]>$raw", "ttml").isEmpty())
    }

    @Test fun subtitleFormatsKeepCueAndKaraokeTimingWithoutStyling() {
        val srt = parseLibraryLyrics("1\n00:00:01,000 --> 00:00:02,000\n<i>First</i> second").single()
        assertEquals(1000L, srt.timeMs)
        assertEquals("First second", srt.text)
        assertTrue(srt.words.isEmpty())
        val vtt = parseLibraryLyrics("WEBVTT\n\n00:01.000 --> 00:02.000 position:20%\nFirst<00:01.450> second").single()
        assertEquals(expected, vtt.words)
        val ass = parseLibraryLyrics("[Script Info]\n[Events]\nFormat: Marked, Start, End, Text\nDialogue: Marked=0,0:00:01.00,0:00:02.00,{\\k45}First{\\kf55} second").single()
        assertEquals(expected, ass.words)
    }

    @Test fun kscAndLyricsfileKeepExplicitWordsAndOffset() {
        val ksc = parseLibraryLyrics("karaoke.TimeOffset := 100;\nkaraoke.add('00:00.900', '00:01.900', '[英][First][ second]', '0,450,550');").single()
        assertEquals(expected, ksc.words)
        val yaml = """
            version: '1.0'
            metadata: {title: Demo, artist: Demo, offset_ms: 100}
            lines:
              - text: First second
                start_ms: 900
                words:
                  - {text: First, start_ms: 900, end_ms: 1350}
                  - {text: ' second', start_ms: 1350, end_ms: 1900}
        """.trimIndent()
        assertEquals(expected, parseLibraryLyrics(yaml).single().words)
        assertTrue(parseLibraryLyrics("version: '1.0'\nmetadata: {}\nlines: []\nplain: Without timing", "lyrics").isEmpty())
    }

}
