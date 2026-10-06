package com.musicone.demo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CachedPlaybackUpgradeTest {
    private fun audio(quality: AudioQuality, cached: Boolean = true): ResolvedPlayback {
        val url = if (cached) offlinePlaybackUrl("QQ:user|qq-song|/M800song.mp3") else "https://example.com/song.flac"
        val track = MusicTrack("qq-song", MusicSource.QQ, "歌曲", "歌手", "专辑", 240_000,
            0, 0, "曲", url, lyrics = listOf(TimedLyric(0, "缓存歌词")))
        return ResolvedPlayback(track, PlaybackSource(url, quality, quality, quality.bitRate, "", false))
    }

    @Test fun cachedTrackKeepsPlayingUntilTargetReadyAndHandoffReadsLatestPosition() = runBlocking {
        val initial = audio(AudioQuality.EXHIGH)
        val ready = CompletableDeferred<ResolvedPlayback>()
        val resolving = CompletableDeferred<Unit>()
        val handedOff = CompletableDeferred<Pair<Long, ResolvedPlayback>>()
        var position = 15_000L
        var playing = initial
        val upgrade = CachedPlaybackUpgrade(this, settleDelayMs = 0L) { _, quality ->
            assertEquals(AudioQuality.HI_RES, quality)
            resolving.complete(Unit)
            ready.await()
        }
        upgrade.start(initial, AudioQuality.HI_RES, { true }) {
            playing = it
            handedOff.complete(position to it)
        }
        resolving.await()
        assertSame(initial, playing)
        position = 19_500
        val next = audio(AudioQuality.HI_RES, false)
        ready.complete(next)
        val result = withTimeout(2_000) { handedOff.await() }
        assertEquals(19_500L, result.first)
        assertEquals(initial.track.lyrics, result.second.track.lyrics)
        assertSame(next, playing)
    }

    @Test fun unavailableOrSameQualityNeverReplacesCompleteCache() = runBlocking {
        val initial = audio(AudioQuality.LOSSLESS)
        val failures = listOf<ResolvedPlayback?>(null, initial,
            audio(AudioQuality.HI_RES, false).let { it.copy(source = it.source.copy(trial = true)) },
            audio(AudioQuality.HI_RES, false).let { it.copy(source = it.source.copy(verificationPending = true)) })
        for (candidate in failures) {
            val requested = CompletableDeferred<Unit>()
            val upgrade = CachedPlaybackUpgrade(this, settleDelayMs = 0L) { _, _ ->
                requested.complete(Unit)
                candidate ?: throw IllegalStateException("断网")
            }
            upgrade.start(initial, AudioQuality.HI_RES, { true }) { fail("不可用音源不能打断缓存") }
            requested.await()
            yield()
            upgrade.cancel()
        }
    }

    @Test fun trackChangeOrManualSelectionCancelsLateAutomaticHandoff() = runBlocking {
        val initial = audio(AudioQuality.EXHIGH)
        val resolving = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<ResolvedPlayback>()
        var current = true
        val upgrade = CachedPlaybackUpgrade(this, settleDelayMs = 0L) { _, _ -> resolving.complete(Unit); ready.await() }
        upgrade.start(initial, AudioQuality.HI_RES, { current }) { fail("旧请求不能覆盖新选择") }
        resolving.await()
        current = false
        ready.complete(audio(AudioQuality.HI_RES, false))
        yield()
        upgrade.cancel()
        upgrade.start(initial, AudioQuality.HI_RES, { true }) { fail("已取消的请求不应交接") }
        upgrade.cancel()
    }

    @Test fun rapidTrackChangeCancelsUpgradeBeforeNetworkRequest() = runBlocking {
        var requested = false
        val upgrade = CachedPlaybackUpgrade(this, settleDelayMs = 80L) { _, _ ->
            requested = true
            audio(AudioQuality.HI_RES, false)
        }

        upgrade.start(audio(AudioQuality.EXHIGH), AudioQuality.HI_RES, { true }) { fail() }
        upgrade.cancel()
        delay(120)

        assertFalse(requested)
    }

    @Test fun matchingCacheAndFreshNetworkSourceDoNotStartAnotherResolution() = runBlocking {
        val upgrade = CachedPlaybackUpgrade(this, settleDelayMs = 0L) { _, _ -> error("无需重复查询") }
        upgrade.start(audio(AudioQuality.HI_RES), AudioQuality.HI_RES, { true }) { fail() }
        upgrade.start(audio(AudioQuality.EXHIGH, false), AudioQuality.HI_RES, { true }) { fail() }
        yield()
        upgrade.cancel()
    }
}
