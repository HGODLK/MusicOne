package com.musicone

import com.musicone.source.sdk.PlaybackAddress
import com.musicone.source.sdk.SourcePlaybackResource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTicketFreshnessTest {
    private fun track(
        id: String = "song-1",
        source: MusicSource = MusicSource.QQ,
        url: String = "https://cdn.example.com/audio.flac",
    ) = MusicTrack(
        id = id,
        source = source,
        title = "测试歌曲",
        artists = "歌手",
        album = "专辑",
        durationMs = 240_000L,
        artworkStart = 0,
        artworkEnd = 0,
        artworkMark = id,
        previewUrl = url,
        lyrics = listOf(TimedLyric(0L, "第一句歌词")),
    )

    private fun playbackSource(
        url: String = "https://cdn.example.com/audio.flac",
        expiresAtEpochMs: Long? = null,
        trial: Boolean = false,
        withResource: Boolean = false,
    ) = PlaybackSource(
        url = url,
        requestedQuality = AudioQuality.LOSSLESS,
        actualQuality = AudioQuality.LOSSLESS,
        bitRate = 900_000,
        format = "flac",
        trial = trial,
        expiresAtEpochMs = expiresAtEpochMs,
        sourceResource = if (withResource) {
            SourcePlaybackResource(
                address = PlaybackAddress(url = url, cacheIdentity = "k1", expiresAtEpochMs = expiresAtEpochMs),
                qualityId = "FLAC",
                format = "flac",
            )
        } else null,
    )

    @Test fun trackerRespectsDeclaredExpiryAcrossDeepSleepClockJump() {
        var monotonicMs = 1_000L
        val wallMs = 1_700_000_000_000L
        val tracker = PlaybackTicketTracker(
            nowMs = { monotonicMs },
            wallClockMs = { wallMs },
            ttlMs = { 18 * 60_000L },
            refreshLeadMs = 60_000L,
        )
        val item = track()
        tracker.record(1L, item, playbackSource(expiresAtEpochMs = wallMs + 50 * 60_000L))

        monotonicMs = 1_000L + 48 * 60_000L + 59_999L
        assertFalse(tracker.needsRenewal(1L, item))

        // 模拟设备后台深度休眠后 elapsedRealtime 跨越到期前 60 秒窗口。
        monotonicMs = 1_000L + 49 * 60_000L
        assertTrue(tracker.needsRenewal(1L, item))
        assertFalse(tracker.needsRenewal(2L, item))
        assertFalse(tracker.needsRenewal(1L, item.copy(previewUrl = "https://cdn.example.com/other.flac")))
    }

    @Test fun trackerFallsBackToDefaultTtlAndSkipsLocalOrOfflineCache() {
        var monotonicMs = 0L
        val tracker = PlaybackTicketTracker(
            nowMs = { monotonicMs },
            wallClockMs = { 10_000L },
            ttlMs = { 18 * 60_000L },
            refreshLeadMs = 60_000L,
        )
        val online = track(source = MusicSource.NETEASE)
        tracker.record(1L, online, playbackSource(expiresAtEpochMs = null))
        monotonicMs = 17 * 60_000L - 1L
        assertFalse(tracker.needsRenewal(1L, online))
        monotonicMs = 17 * 60_000L
        assertTrue(tracker.needsRenewal(1L, online))

        val local = track(source = MusicSource.LOCAL, url = "content://media/1")
        tracker.record(2L, local, null)
        monotonicMs = 100 * 60_000L
        assertFalse(tracker.needsRenewal(2L, local))

        val offline = track(url = offlinePlaybackUrl("QQ:guest|song-1|flac"))
        tracker.record(3L, offline, playbackSource(url = offline.previewUrl, expiresAtEpochMs = 10_000L))
        assertFalse(tracker.needsRenewal(3L, offline))
    }

    @Test fun refresherPrefersRecoverAndFallsBackToResolveWithoutDowngradingToTrial() = runBlocking {
        val original = track()
        val key = PlaybackRequestKey(original.id, 7L)
        var recoverCalls = 0
        var resolveCalls = 0
        var delivered: ResolvedPlayback? = null

        val refresher = PlaybackTicketRefresher(
            scope = this,
            recover = { _, _, _ ->
                recoverCalls++
                error("恢复资料已过期")
            },
            resolve = { item, _, _ ->
                resolveCalls++
                val freshUrl = "https://cdn.example.com/fresh.flac"
                ResolvedPlayback(item.copy(previewUrl = freshUrl), playbackSource(url = freshUrl))
            },
            isCurrent = { it == key },
            onRenewed = { _, resolved -> delivered = resolved },
        )

        refresher.renewIfIdle(
            track = original,
            key = key,
            recoverQuality = AudioQuality.LOSSLESS,
            fallbackQuality = AudioQuality.LOSSLESS,
            previous = playbackSource(withResource = true),
        )
        // 协程在同作用域执行完成后检验回退结果。
        kotlinx.coroutines.yield()
        assertEquals(1, recoverCalls)
        assertEquals(1, resolveCalls)
        assertEquals("https://cdn.example.com/fresh.flac", delivered?.source?.url)

        // 完整音源在静默刷新时不能被试听片段替换。
        var trialDelivered = false
        val trialGuard = PlaybackTicketRefresher(
            scope = this,
            recover = { item, _, _ ->
                val trialUrl = "https://cdn.example.com/trial.mp3"
                ResolvedPlayback(item.copy(previewUrl = trialUrl), playbackSource(url = trialUrl, trial = true))
            },
            resolve = { _, _, _ -> error("不应走到 resolve") },
            isCurrent = { it == key },
            onRenewed = { _, _ -> trialDelivered = true },
        )
        trialGuard.renewIfIdle(
            track = original,
            key = key,
            recoverQuality = AudioQuality.LOSSLESS,
            fallbackQuality = AudioQuality.LOSSLESS,
            previous = playbackSource(trial = false, withResource = true),
        )
        kotlinx.coroutines.yield()
        assertFalse(trialDelivered)
    }

    @Test fun refresherDeduplicatesInFlightKeyAndRespectsCancellation() = runBlocking {
        val original = track()
        val key = PlaybackRequestKey(original.id, 3L)
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var delivered = 0

        val refresher = PlaybackTicketRefresher(
            scope = this,
            recover = { item, _, _ ->
                calls++
                started.complete(Unit)
                gate.await()
                val freshUrl = "https://cdn.example.com/fresh.flac"
                ResolvedPlayback(item.copy(previewUrl = freshUrl), playbackSource(url = freshUrl))
            },
            resolve = { _, _, _ -> error("不应走到 resolve") },
            isCurrent = { it == key },
            onRenewed = { _, _ -> delivered++ },
        )

        val previous = playbackSource(withResource = true)
        refresher.renewIfIdle(original, key, AudioQuality.LOSSLESS, AudioQuality.LOSSLESS, previous)
        started.await()
        refresher.renewIfIdle(original, key, AudioQuality.LOSSLESS, AudioQuality.LOSSLESS, previous)
        assertTrue(refresher.isRenewing)
        refresher.cancel()
        gate.complete(Unit)
        kotlinx.coroutines.yield()
        assertEquals(1, calls)
        assertEquals(0, delivered)
        assertFalse(refresher.isRenewing)
    }

    @Test fun withRenewedPlaybackUpdatesOnlyUrlAndPreservesLyricsAndQualities() {
        val original = track()
        val state = MusicOneUiState(
            currentTrack = original,
            queue = listOf(original),
            isPlaying = false,
            activeQuality = AudioQuality.LOSSLESS,
            availableQualities = listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS),
            lyricLoadState = LyricLoadState.READY,
        )
        val freshUrl = "https://cdn.example.com/renewed.flac"
        val updated = state.withRenewedPlayback(
            ResolvedPlayback(original.copy(previewUrl = freshUrl, lyrics = emptyList()), playbackSource(url = freshUrl)),
        )
        assertEquals(freshUrl, updated.currentTrack?.previewUrl)
        assertEquals(freshUrl, updated.queue.single().previewUrl)
        assertEquals(original.lyrics, updated.currentTrack?.lyrics)
        assertEquals(state.activeQuality, updated.activeQuality)
        assertEquals(state.availableQualities, updated.availableQualities)
        assertEquals(LyricLoadState.READY, updated.lyricLoadState)
        assertFalse(updated.isPlaying)
    }
}
