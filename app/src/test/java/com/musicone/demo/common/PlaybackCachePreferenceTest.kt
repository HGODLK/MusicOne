package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackCachePreferenceTest {
    private fun playback(quality: AudioQuality): ResolvedPlayback {
        val track = MusicTrack("qq-test", MusicSource.QQ, "歌曲", "歌手", "", 180_000L, 0, 0, "test", "")
        return ResolvedPlayback(track, PlaybackSource("test", AudioQuality.DOLBY, quality, quality.bitRate, "", false))
    }

    @Test fun ordinaryCacheDoesNotPreventAnOnlineDolbySelection() = runBlocking {
        val accesses = mutableListOf<String>()
        val result = resolvePlaybackCacheFirst(true, cached = { other ->
            accesses += "cache:$other"
            playback(AudioQuality.LOSSLESS).takeIf { other }
        }, online = {
            accesses += "online"
            playback(AudioQuality.DOLBY)
        })
        assertEquals(AudioQuality.DOLBY, result.source.actualQuality)
        assertEquals(listOf("cache:false", "online"), accesses)
    }

    @Test fun exactDolbyCachePlaysImmediatelyWithoutAnotherTicket() = runBlocking {
        val dolby = playback(AudioQuality.DOLBY)
        assertSame(dolby, resolvePlaybackCacheFirst(true, cached = { dolby },
            online = { error("同档完整缓存不应重新取票") }))
    }

    @Test fun offlineOrOrdinaryPreferenceKeepsImmediateCachedPlayback() = runBlocking {
        val cached = playback(AudioQuality.LOSSLESS)
        assertSame(cached, resolvePlaybackCacheFirst(false, cached = { other ->
            assertTrue(other)
            cached
        }, online = { error("离线缓存应立即起播") }))
    }

    @Test fun failedOnlineResolutionCanStillUseOrdinaryCache() = runBlocking {
        val accesses = mutableListOf<Boolean>()
        val cached = playback(AudioQuality.LOSSLESS)
        assertSame(cached, resolvePlaybackCacheFirst(true, cached = { other ->
            accesses += other
            cached.takeIf { other }
        }, online = { throw PlatformApiException("音源暂不可用") }))
        assertEquals(listOf(false, true), accesses)
    }

    @Test fun cancelledSelectionCannotFallBackAndStartAnOldTrack() = runBlocking {
        val cancelled = CancellationException("切歌取消")
        try {
            resolvePlaybackCacheFirst(true, cached = { other ->
                assertFalse(other)
                null
            }, online = { throw cancelled })
            fail("取消必须透传")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }

    @Test fun rejectedSessionIsNotHiddenByCachedFallback() = runBlocking {
        val rejected = QqCredentialExpiredException(401, "测试")
        try {
            resolvePlaybackCacheFirst(true, cached = { other ->
                assertFalse(other)
                null
            }, online = { throw rejected })
            fail("账号错误必须透传")
        } catch (actual: QqCredentialExpiredException) {
            assertSame(rejected, actual)
        }
    }
}
