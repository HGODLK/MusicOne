package com.musicone

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricCacheRecoveryTest {
    @Test fun failureWithoutCachedLyricsMustRemainRetryable() = runBlocking {
        try {
            fetchLyricsPreservingFallback(track, emptyList()) { throw java.io.IOException("断网") }
            fail("没有缓存时不能把请求失败伪装成无歌词")
        } catch (_: java.io.IOException) { }
    }

    @Test fun failedNetworkRefreshPreservesLegacyFallbackAndCancellationPropagates() = runBlocking {
        val legacy = listOf(TimedLyric(0, "旧歌词"))
        assertEquals(legacy, fetchLyricsPreservingFallback(track, legacy) { throw java.io.IOException("断网") })
        try {
            fetchLyricsPreservingFallback(track, legacy) { throw CancellationException("取消") }
            fail("取消请求不能交付旧数据")
        } catch (_: CancellationException) { }
    }

    @Test fun kugouLrcFallbackDoesNotHideExistingVerbatimLyrics() = runBlocking {
        val track = MusicTrack("kugou-song", MusicSource.KUGOU, "歌曲", "歌手", "", 210_000, 0, 0, "", "")
        val verbatim = listOf(TimedLyric(0, "逐字歌词", words = listOf(LyricWord(0, 400, "逐字歌词")),
            parserVersion = 1))
        val plain = listOf(TimedLyric(0, "普通歌词"))
        assertEquals(verbatim, fetchLyricsPreservingFallback(track, verbatim, preserveVerbatimFallback = true) { plain })
        assertEquals(plain, fetchLyricsPreservingFallback(track, verbatim, preserveVerbatimFallback = false) { plain })
    }
    private val track = MusicTrack("qq-song", MusicSource.QQ, "歌曲", "歌手", "", 210_000, 0, 0, "", "")
    private val fresh = listOf(TimedLyric(16_175, "第一句"), TimedLyric(22_305, "第二句"))
        .map { it.copy(parserVersion = 2) }

    @Test fun legacyArrayWithWordsRemainsUnverifiedAndNewCacheRoundTrips() {
        val legacy = JSONArray().apply {
            repeat(26) { index -> put(JSONObject().put("time", index * 600).put("text", "署名$index")
                .put("words", JSONArray().put(JSONObject().put("s", index * 600).put("e", index * 600 + 400).put("t", "署名$index")))) }
        }
        val decoded = decodeCachedLyrics(legacy.toString())
        assertEquals(26, decoded.size)
        assertEquals(26, decoded.count { it.words.isNotEmpty() })
        assertTrue(hasReusableLyrics(decoded, parserVersion = 0))
        assertFalse(hasReusableLyrics(decoded, parserVersion = 2))
        assertTrue(hasReusableLyrics(fresh, parserVersion = 2))
        assertEquals(fresh, decodeCachedLyrics(encodeCachedLyrics(fresh)))
        val future = JSONObject(encodeCachedLyrics(fresh)).put("version", 99)
        assertTrue(hasReusableLyrics(decodeCachedLyrics(future.toString()), parserVersion = 0))
        assertFalse(hasReusableLyrics(decodeCachedLyrics(future.toString()), parserVersion = 2))
    }

    @Test fun loaderFetchesInvalidTimelineAndPublishesReplacement() = runBlocking {
        val legacy = track.copy(lyrics = listOf(TimedLyric(-1, "第一句", words = listOf(LyricWord(16_175, 16_800, "第一句")))))
        val updates = mutableListOf<PlaybackLyricsUpdate>()
        var calls = 0
        val loader = PlaybackLyricsLoader(this, { calls++; fresh }, { true }, updates::add)
        loader.load(legacy, PlaybackRequestKey(track.id, 1))
        yield()
        assertEquals(1, calls)
        assertEquals(fresh, updates.last().lyrics)
        loader.cancel()
    }

    @Test fun replacementUpdatesQueueAndResolvedDataCannotRestoreLegacyLyrics() {
        val legacy = track.copy(lyrics = listOf(TimedLyric(16_175, "第一句")))
        val state = MusicOneUiState(currentTrack = legacy, queue = listOf(legacy))
        val updated = state.withLyricsUpdate(PlaybackLyricsUpdate(PlaybackRequestKey(track.id, 1), LyricLoadState.READY, fresh))
        assertEquals(fresh, updated.queue.single().lyrics)
        val source = PlaybackSource("url", AudioQuality.STANDARD, AudioQuality.STANDARD, 0, "", false)
        // 歌词更新已提交后，迟到的播放解析结果不能把旧歌词重新覆盖回来。
        val resolved = updated.withResolvedPlayback(PlaybackTrackEvent.Ready(PlaybackRequestKey(track.id, 1),
            ResolvedPlayback(legacy, source), AudioQuality.STANDARD, AudioQuality.STANDARD, 0, false))
        assertEquals(fresh, resolved.currentTrack?.lyrics)
        assertEquals(fresh, resolved.queue.single().lyrics)
        // 没有已加载的解析器声明时，已有合法旧缓存仍可离线使用。
        val offline = state.withResolvedPlayback(PlaybackTrackEvent.Ready(PlaybackRequestKey(track.id, 1),
            ResolvedPlayback(track.copy(lyrics = fresh), source), AudioQuality.STANDARD, AudioQuality.STANDARD, 0, false))
        assertEquals(legacy.lyrics, offline.currentTrack?.lyrics)
    }

}
