package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QqPlaybackSourceCacheTest {
    @Test
    fun 短期复用完整音源但不跨凭证媒体标识或过期时间() {
        var now = 0L
        val cache = QqPlaybackSourceCache { now }
        cache.remember(track, "credential-one", source)
        assertEquals(source, cache.get(track, "credential-one", source.actualQuality))
        assertNull(cache.get(track, "credential-two", source.actualQuality))
        assertNull(cache.get(track.copy(mediaId = "different"), "credential-one", source.actualQuality))
        now = 30_000L
        assertNull(cache.get(track, "credential-one", source.actualQuality))
    }

    @Test
    fun 试听或待验证音源不能复用为已确认音质() {
        val cache = QqPlaybackSourceCache { 0L }
        cache.remember(track, "credential", source.copy(trial = true))
        assertNull(cache.get(track, "credential", source.actualQuality))
        cache.remember(track, "credential", source.copy(verificationPending = true))
        assertNull(cache.get(track, "credential", source.actualQuality))
    }

    private val track = MusicTrack(
        id = "qq-test", source = MusicSource.QQ, title = "测试", artists = "测试", album = "测试",
        durationMs = 180_000L, artworkStart = 0L, artworkEnd = 0L, artworkMark = "测",
        previewUrl = "", mediaId = "media", songMid = "song",
    )
    private val source = PlaybackSource(
        "https://example.com/song.mp3", AudioQuality.STANDARD, AudioQuality.STANDARD, 128_000, "mp3", false,
    )
}
