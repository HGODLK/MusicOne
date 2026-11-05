package com.musicone.source.qq.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class QqPlaybackStartupTest {
    @Test
    fun 缺失文件按档位记住一天且不跨歌曲() {
        var now = 0L
        val cache = QqPlaybackMissCache({ now })
        val hiRes = qqQualitySpecs(AudioQuality.HI_RES).single()
        cache.remember(track, AudioQuality.HI_RES, hiRes)
        assertTrue(cache.contains(track, AudioQuality.HI_RES, hiRes))
        assertFalse(cache.contains(track, AudioQuality.LOSSLESS, qqQualitySpecs(AudioQuality.LOSSLESS).single()))
        assertFalse(cache.contains(track.withQqSongMid("other"), AudioQuality.HI_RES, hiRes))
        now = 24 * 60 * 60_000L
        assertFalse(cache.contains(track, AudioQuality.HI_RES, hiRes))
    }

    @Test
    fun 只为高档位并行准备无损() {
        assertEquals(AudioQuality.LOSSLESS, qqSpeculativeFallback(AudioQuality.HI_RES))
        assertEquals(AudioQuality.LOSSLESS, qqSpeculativeFallback(AudioQuality.DOLBY))
        assertNull(qqSpeculativeFallback(AudioQuality.LOSSLESS))
        assertNull(qqSpeculativeFallback(AudioQuality.EXHIGH))
    }

    @Test
    fun 降档请求接上同一个预取任务() = runBlocking {
        val prefetch = QqPlaybackFallbackPrefetch(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        prefetch.start(track, "cookie", AudioQuality.LOSSLESS) { calls.incrementAndGet(); gate.await(); source }
        prefetch.start(track, "cookie", AudioQuality.LOSSLESS) { calls.incrementAndGet(); source }
        gate.complete(Unit)
        assertEquals(source, prefetch.take(track, "cookie", AudioQuality.LOSSLESS))
        assertEquals(1, calls.get())
        assertNull(prefetch.take(track, "cookie", AudioQuality.LOSSLESS))
    }

    @Test
    fun 预取失败原样交给降档请求_取消则重新请求() = runBlocking {
        val prefetch = QqPlaybackFallbackPrefetch(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val failed = prefetch.start(track, "cookie", AudioQuality.LOSSLESS) {
            throw QqPlaybackFileMissingException(AudioQuality.LOSSLESS)
        }
        runCatching { failed.await() }
        try {
            prefetch.take(track, "cookie", AudioQuality.LOSSLESS)
            fail("预取失败应原样抛出")
        } catch (_: QqPlaybackFileMissingException) {}
        val gate = CompletableDeferred<Unit>()
        val cancelled = prefetch.start(track, "cookie", AudioQuality.LOSSLESS) { gate.await(); source }
        prefetch.cancel(cancelled)
        assertNull(prefetch.take(track, "cookie", AudioQuality.LOSSLESS))
    }

    @Test
    fun 换歌后取消上一首的预取() = runBlocking {
        val prefetch = QqPlaybackFallbackPrefetch(CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val gate = CompletableDeferred<Unit>()
        val old = prefetch.start(track, "cookie", AudioQuality.LOSSLESS) { gate.await(); source }
        prefetch.start(track.copy(id = "qq-next"), "cookie", AudioQuality.LOSSLESS) { source }
        assertTrue(old.isCancelled)
        assertNull(prefetch.take(track, "cookie", AudioQuality.LOSSLESS))
    }

    private val track = MusicTrack(
        id = "qq-test", source = MusicSource.QQ, title = "测试", artists = "测试", album = "测试",
        durationMs = 180_000L, artworkStart = 0L, artworkEnd = 0L, artworkMark = "测",
        previewUrl = "", mediaId = "media",
    ).withQqSongMid("song")
    private val source = PlaybackSource(
        "https://example.com/song.flac", AudioQuality.LOSSLESS, AudioQuality.LOSSLESS, 900_000, "flac", false,
    )
}
