package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseLyricsTest {
    private val track = MusicTrack("netease-song", MusicSource.NETEASE, "歌曲", "歌手", "", 210_000, 0, 0, "", "")

    private fun response(vararg lyrics: Pair<String, String>) = JSONObject().apply {
        lyrics.forEach { (key, text) -> put(key, JSONObject().put("lyric", text)) }
    }

    @Test fun yrcKeepsAbsoluteWordTimingAndOriginalSegments() {
        val lines = parseNeteaseYrc("""
            {"t":0,"c":[{"tx":"作词"}]}
            [10000,2000](10100,500,0)Hello (10600,1400,0)世界！
        """.trimIndent())
        assertEquals(1, lines.size)
        assertEquals(10_000L, lines.single().timeMs)
        assertEquals("Hello 世界！", lines.single().text)
        assertEquals(listOf(LyricWord(10_100, 10_600, "Hello "), LyricWord(10_600, 12_000, "世界！")), lines.single().words)
        assertEquals(listOf(0, 6), lyricWordRanges(lines.single()).map { it.start })
    }

    @Test fun malformedRowsDoNotLoseFollowingValidRows() {
        val lines = parseNeteaseYrc("""
            [0,1000]普通文本
            [1000,1000](9223372036854775807,100,0)溢出
            [2000,1000](2200,100,0)后(2100,100,0)前
            [3000,1000](3000,0,0)光
        """.trimIndent())
        assertEquals(listOf("光"), lines.map { it.text })
        assertEquals(3000L, lines.single().words.single().endMs)
    }

    @Test fun yrcTranslationAcceptsLrcContentAndTakesPriority() {
        val lines = parseNeteaseLyrics(response(
            "yrc" to "[10000,1000](10000,1000,0)Hello",
            "lrc" to "[00:01.00]旧版原文",
            "ytlrc" to "[00:10.00]你好",
            "tlyric" to "[00:01.00]旧版翻译",
        ))
        assertEquals("Hello", lines.single().text)
        assertEquals("你好", lines.single().translation)
        assertTrue(lines.single().words.isNotEmpty())
        assertTrue(hasReusableLyrics(track, lines))
    }

    @Test fun invalidYrcFallsBackToRealLineLyricsWithoutSyntheticWords() {
        val lines = parseNeteaseLyrics(response(
            "yrc" to "[0,1000]没有有效词时间",
            "lrc" to "[00:01.00][00:04.00]Hello",
            "tlyric" to "[00:01.00]你好\n[00:04.00]您好",
        ))
        assertEquals(listOf(1000L, 4000L), lines.map { it.timeMs })
        assertTrue(lines.all { it.words.isEmpty() })
        assertEquals(listOf("你好", "您好"), lines.map { it.translation })
        assertFalse(hasReusableLyrics(track, lines))
        assertTrue(needsVerbatimLyricsRefresh(track.copy(lyrics = lines)))
    }

    @Test fun missingTranslationsAndCreditHeadersDoNotReuseOrShiftAnnotations() {
        val original = listOf(TimedLyric(0, "作词"), TimedLyric(1000, "作曲"),
            TimedLyric(2842, "first"), TimedLyric(5786, "second"), TimedLyric(6200, "third"))
        val translations = listOf(TimedLyric(0, "//"), TimedLyric(2842, "第一句"), TimedLyric(6200, "第三句"))
        assertEquals(listOf(null, null, "第一句", null, "第三句"),
            alignNeteaseTranslation(original, translations).map { it.translation })
    }

    @Test fun consistentOffsetIsCorrectedWithoutMovingOriginalOrWordTimes() {
        val original = listOf(1000L, 7000L, 13000L).map {
            TimedLyric(it, "原文", words = listOf(LyricWord(it, it + 500, "原文")))
        }
        val translated = listOf(3000L, 9000L, 15000L).map { TimedLyric(it, "翻译") }
        val aligned = alignNeteaseTranslation(original, translated)
        assertTrue(aligned.all { it.translation == "翻译" })
        assertEquals(original.map { it.timeMs }, aligned.map { it.timeMs })
        assertEquals(original.map { it.words }, aligned.map { it.words })
    }

    @Test fun richFailureDeliversUnconfirmedLrcAndCancellationDoesNotFallback() {
        val legacy = response("lrc" to "[00:01.00]旧接口歌词")
        val lines = loadNeteaseLyrics({ throw java.io.IOException("断网") }, { legacy })
        assertEquals("旧接口歌词", lines.single().text)
        assertFalse(hasReusableLyrics(track, lines))
        assertEquals(lines, decodeCachedLyrics(encodeCachedLyrics(lines)))
        assertTrue(needsVerbatimLyricsRefresh(track.copy(lyrics = lines)))
        var fallbackCalled = false
        try {
            loadNeteaseLyrics({ throw CancellationException("取消") }, { fallbackCalled = true; legacy })
            fail("取消不能触发普通歌词回退")
        } catch (_: CancellationException) { }
        assertFalse(fallbackCalled)
    }

    @Test fun confirmedNeteaseCacheRoundTripsAndLegacyCacheNeedsRefresh() {
        val legacy = listOf(TimedLyric(1000, "旧歌词"))
        assertFalse(hasReusableLyrics(track, legacy))
        val current = parseNeteaseLyrics(response("yrc" to "[1000,1000](1000,1000,0)新歌词"))
        assertEquals(current, decodeCachedLyrics(encodeCachedLyrics(current)))
        assertTrue(hasReusableLyrics(track, decodeCachedLyrics(encodeCachedLyrics(current))))
        assertFalse(needsVerbatimLyricsRefresh(track.copy(lyrics = current)))
    }

    @Test fun loaderShowsLegacyWhileRefreshingAndRechecksLineLyrics() = runBlocking {
        val legacy = track.copy(lyrics = listOf(TimedLyric(1000, "旧歌词")))
        val current = parseNeteaseLyrics(response("lrc" to "[00:01.00]已确认普通歌词"))
        val updates = mutableListOf<PlaybackLyricsUpdate>()
        var calls = 0
        val loader = PlaybackLyricsLoader(this, { calls++; current }, { true }, updates::add)
        loader.load(legacy, PlaybackRequestKey(track.id, 1))
        assertEquals(legacy.lyrics, updates.last().lyrics)
        assertEquals(LyricLoadState.READY, updates.last().state)
        yield()
        assertEquals(1, calls)
        assertEquals(current, updates.last().lyrics)
        loader.load(track.copy(lyrics = current), PlaybackRequestKey(track.id, 2))
        yield()
        assertEquals(2, calls)
        loader.cancel()
    }
}
