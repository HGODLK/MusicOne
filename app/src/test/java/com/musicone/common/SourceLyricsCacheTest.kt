package com.musicone

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class SourceLyricsCacheTest {
    @get:org.junit.Rule val sources = InstalledSourceContractsRule()
    private val track = MusicTrack("source-song", MusicSource.fromId("org.example.source"), "歌曲", "歌手", "", 210_000, 0, 0, "", "")
    @Test fun parsedWordsKeepTheirUtf16IndicesInTheRenderer() {
        val line = TimedLyric(10_000, "Hello 世界！", words = listOf(LyricWord(10_100, 10_600, "Hello "), LyricWord(10_600, 12_000, "世界！")))
        assertEquals(listOf(0, 6), lyricWordRanges(line).map { it.start })
    }
    @Test fun confirmedAndLegacyLyricsRoundTripWithoutLosingParserVersion() {
        val legacy = listOf(TimedLyric(1000, "旧歌词"))
        val current = listOf(TimedLyric(1000, "新歌词", words = listOf(LyricWord(1000, 2000, "新歌词")), parserVersion = 2))
        assertEquals(legacy, decodeCachedLyrics(encodeCachedLyrics(legacy)))
        assertEquals(current, decodeCachedLyrics(encodeCachedLyrics(current)))
        assertEquals(2, decodeCachedLyrics(encodeCachedLyrics(current)).single().parserVersion)
    }
    @Test fun loaderShowsLegacyWhileRefreshingAndRechecksLineLyrics() = runBlocking {
        val legacy = track.copy(lyrics = listOf(TimedLyric(1000, "旧歌词")))
        val current = listOf(TimedLyric(1000, "已确认普通歌词", parserVersion = 2))
        val updates = mutableListOf<PlaybackLyricsUpdate>()
        var calls = 0
        val refreshed = Channel<Int>(Channel.UNLIMITED)
        val loader = PlaybackLyricsLoader(this, { calls++; current }, { true }, {
            updates += it
            // 等待该代次真实请求返回，不假定一次 yield 就能跑完超时包装内的协程。
            if (it.lyrics == current && calls.toLong() == it.key.generation) refreshed.trySend(calls)
        })
        val previous = ExperiencePreferences.options
        try {
            // 普通歌词再次核对依赖逐字开关，测试自行建立前置条件并恢复全局偏好。
            ExperiencePreferences.update(previous.copy(verbatimLyricAnimation = true))
            loader.load(legacy, PlaybackRequestKey(track.id, 1))
            assertEquals(legacy.lyrics, updates.last().lyrics)
            assertEquals(LyricLoadState.READY, updates.last().state)
            assertEquals(1, withTimeout(3_000) { refreshed.receive() })
            assertEquals(1, calls)
            assertEquals(current, updates.last().lyrics)
            loader.load(track.copy(lyrics = current), PlaybackRequestKey(track.id, 2))
            assertEquals(2, withTimeout(3_000) { refreshed.receive() })
            assertEquals(2, calls)
        } finally {
            loader.cancel()
            ExperiencePreferences.update(previous)
        }
    }
}
