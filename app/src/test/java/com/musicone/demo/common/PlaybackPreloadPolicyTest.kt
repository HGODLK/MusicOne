package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaybackPreloadPolicyTest {
    private fun track(id: String, source: MusicSource) = MusicTrack(id, source, id, "歌手", "", 180_000L, 0, 0, id, "")
    private val local = track("local", MusicSource.LOCAL)
    private val local2 = track("local2", MusicSource.LOCAL)
    private val netease = track("netease", MusicSource.NETEASE)
    private val bili = track("bili", MusicSource.BILIBILI)
    private val qq = track("qq", MusicSource.QQ)
    private val kugou = track("kugou", MusicSource.KUGOU)
    private val queue = listOf(local, local2, netease, bili, qq, kugou)
    private val state = MusicOneUiState(currentTrack = local, queue = queue)
    private fun audio(value: MusicOneUiState) = nextPlaybackPreloadTracks(value, PRELOAD_AUDIO_SOURCES, 2)
    private fun lyrics(value: MusicOneUiState) = nextPlaybackPreloadTracks(value, PRELOAD_LYRIC_SOURCES, 5)

    @Test fun localPlaybackSkipsFilesAndPreparesTwoOnlineTracksAcrossSources() {
        assertEquals(listOf(netease, bili), audio(state))
        assertEquals(listOf(netease, qq, kugou), lyrics(state))
    }
    @Test fun everyCurrentSourceCanPrepareOtherPlatforms() {
        for (source in MusicSource.entries) {
            val current = local.copy(source = source)
            assertEquals(listOf(netease, bili), audio(state.copy(currentTrack = current, queue = listOf(current) + queue.drop(1))))
        }
    }
    @Test fun shuffledQueueOrderAndInsertedNextAreRespected() {
        assertEquals(listOf(kugou, qq), audio(state.copy(shuffle = true, queue = listOf(local, kugou, qq, bili))))
        assertEquals(listOf(qq, kugou), audio(state.copy(queueOrder = PlaybackQueueOrder(nextAfterId = local.id, nextTrackId = qq.id))))
    }
    @Test fun singleRepeatQualitySwitchAndMissingCurrentDoNotPrepare() {
        assertTrue(audio(state.copy(repeatMode = RepeatMode.ONE)).isEmpty())
        assertTrue(audio(state.copy(qualityChanging = true)).isEmpty())
        assertTrue(audio(state.copy(currentTrack = null)).isEmpty())
        assertTrue(audio(state.copy(queue = listOf(qq))).isEmpty())
        assertTrue(audio(state.copy(queue = listOf(local))).isEmpty())
    }
    @Test fun radioAndNonLoopingQueueDoNotWrapIntoHistory() {
        val last = state.copy(currentTrack = kugou)
        assertEquals(listOf(netease, bili), audio(last))
        assertTrue(audio(last.copy(repeatMode = RepeatMode.OFF)).isEmpty())
        assertTrue(audio(last.copy(qqRadioActive = true)).isEmpty())
    }
    @Test fun unavailableAndDuplicateSongsAreSkippedWithoutRepeatingCurrent() {
        val value = state.copy(queue = listOf(local, bili.copy(playable = false), qq, qq, kugou, local))
        assertEquals(listOf(qq, kugou), audio(value))
        assertEquals(listOf(qq, kugou), lyrics(value))
    }
    @Test fun lyricsPrepareFiveEligibleSongsAndNeverRequestBilibili() {
        val songs = (1..7).map { track("$it", if (it % 2 == 0) MusicSource.KUGOU else MusicSource.NETEASE) }
        assertEquals(songs.take(5), lyrics(state.copy(queue = listOf(local, bili) + songs)))
    }
    @Test fun shortLoopReturnsAvailableTargetsOnlyOnce() {
        assertEquals(listOf(qq), audio(state.copy(queue = listOf(local, qq))))
        assertEquals(listOf(qq), lyrics(state.copy(queue = listOf(local, qq))))
    }
    @Test fun twentySecondBudgetUsesLongArithmeticWithoutEightMiBLimit() {
        assertEquals(320_000L * 20 / 8 + 128 * 1024L, playbackPreloadBytes(320_000))
        assertTrue(playbackPreloadBytes(8_000_000) > 8 * 1024 * 1024L)
        assertEquals(320_000L * 5 / 8 + 128 * 1024L, playbackPreloadBytes(320_000, 5_000L))
        assertTrue(playbackPreloadBytes(Int.MAX_VALUE) > 0L)
        assertEquals(playbackPreloadBytes(128_000), playbackPreloadBytes(0))
    }
    @Test fun preloadProbeAndDownloadScheduleFollowsConfirmedTiming() {
        assertEquals(listOf(0L, 2_000L, 5_000L, 6_500L, 8_000L), (0..4).map(::playbackProbeDelayMs))
        assertEquals(280L, playbackDownloadDelayMs(0))
        assertEquals(2_000L, playbackDownloadDelayMs(1))
        assertEquals(3_000L, playbackDownloadDelayMs(0, conservative = true))
        assertEquals(10_000L, playbackDownloadDelayMs(1, conservative = true))
        assertNull(playbackDownloadDelayMs(2))
        assertEquals(5, PLAYBACK_PRELOAD_PROBE_COUNT)
        assertEquals(2, PLAYBACK_PRELOAD_DOWNLOAD_COUNT)
    }

    @Test fun probeNeedsPlaybackButDownloadWaitsForThreeSecondsOfBuffer() {
        assertFalse(playbackPreloadCanProbe(false))
        assertTrue(playbackPreloadCanProbe(true))
        assertTrue(playbackPreloadCanDownload(true, 3_000L))
        assertFalse(playbackPreloadCanDownload(true, 2_999L))
        assertFalse(playbackPreloadCanDownload(false, 60_000L))
        assertTrue(playbackPreloadCanDownload(true, 0L, local = true))
    }

    @Test fun ticketTtlMatchesPlatformWindows() {
        assertEquals(50 * 60_000L, playbackTicketTtlMs(MusicSource.QQ))
        assertEquals(18 * 60_000L, playbackTicketTtlMs(MusicSource.NETEASE))
        assertEquals(18 * 60_000L, playbackTicketTtlMs(MusicSource.KUGOU))
        assertEquals(90 * 60_000L, playbackTicketTtlMs(MusicSource.BILIBILI))
    }

    @Test fun dolbyLadderSkipsHiResButHiResLadderKeepsIt() {
        assertEquals(
            listOf(AudioQuality.DOLBY, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD),
            playbackQualityLadder(MusicSource.QQ, AudioQuality.DOLBY),
        )
        assertEquals(
            listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD),
            playbackQualityLadder(MusicSource.QQ, AudioQuality.HI_RES),
        )
        assertEquals(
            listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD),
            playbackQualityLadder(MusicSource.KUGOU, AudioQuality.HI_RES),
        )
        assertFalse(playbackQualityAllowed(MusicSource.QQ, AudioQuality.HI_RES, AudioQuality.DOLBY))
        assertTrue(playbackQualityAllowed(MusicSource.QQ, AudioQuality.LOSSLESS, AudioQuality.DOLBY))
        assertTrue(playbackQualityAllowed(MusicSource.QQ, AudioQuality.HI_RES, AudioQuality.HI_RES))
        assertFalse(playbackQualityAllowed(MusicSource.QQ, AudioQuality.DOLBY, AudioQuality.HI_RES))
        assertTrue(playbackQualityAllowed(MusicSource.BILIBILI, AudioQuality.HI_RES, AudioQuality.DOLBY))
        assertEquals(
            listOf(AudioQuality.DOLBY, AudioQuality.HI_RES, AudioQuality.STANDARD),
            playbackQualityLadder(MusicSource.BILIBILI, AudioQuality.DOLBY),
        )
    }

    @Test fun cachedHiResFallsBackToLosslessUnderDolbyPreference() {
        assertEquals(AudioQuality.HI_RES, playbackCachedStart(AudioQuality.HI_RES, MusicSource.QQ, AudioQuality.HI_RES))
        assertEquals(AudioQuality.LOSSLESS, playbackCachedStart(AudioQuality.HI_RES, MusicSource.QQ, AudioQuality.DOLBY))
        assertEquals(AudioQuality.HI_RES, playbackCachedStart(AudioQuality.HI_RES, MusicSource.BILIBILI, AudioQuality.DOLBY))
        assertEquals(AudioQuality.LOSSLESS, playbackCachedStart(AudioQuality.LOSSLESS, MusicSource.QQ, AudioQuality.HI_RES))
        assertNull(playbackCachedStart(AudioQuality.DOLBY, MusicSource.QQ, AudioQuality.LOSSLESS))
        assertNull(playbackCachedStart(null, MusicSource.QQ, AudioQuality.HI_RES))
    }

    @Test fun qualityCacheKeySeparatesSourceAccountVipAndTrack() {
        val base = playbackQualityCacheKey(MusicSource.QQ, "qq-1", "QQ:100", vip = false)
        assertNotEquals(base, playbackQualityCacheKey(MusicSource.QQ, "qq-2", "QQ:100", vip = false))
        assertNotEquals(base, playbackQualityCacheKey(MusicSource.QQ, "qq-1", "QQ:200", vip = false))
        assertNotEquals(base, playbackQualityCacheKey(MusicSource.QQ, "qq-1", "QQ:100", vip = true))
        assertNotEquals(base, playbackQualityCacheKey(MusicSource.NETEASE, "qq-1", "QQ:100", vip = false))
    }

    @Test fun timeoutAboveMakesResultTentativeOnly() {
        assertFalse(playbackProbeCacheable(AudioQuality.EXHIGH, setOf(AudioQuality.HI_RES)))
        assertFalse(playbackProbeCacheable(AudioQuality.LOSSLESS, setOf(AudioQuality.HI_RES)))
        assertTrue(playbackProbeCacheable(AudioQuality.LOSSLESS, emptySet()))
        assertTrue(playbackProbeCacheable(AudioQuality.HI_RES, setOf(AudioQuality.LOSSLESS)))
        assertFalse(playbackProbeCacheable(AudioQuality.EXHIGH, setOf(AudioQuality.LOSSLESS)))
    }

    @Test fun comparisonWalksFromPreferredDownToCachedTier() {
        assertEquals(
            listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS, AudioQuality.EXHIGH),
            playbackComparisonLadder(MusicSource.QQ, AudioQuality.HI_RES, AudioQuality.EXHIGH),
        )
        assertEquals(
            listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS),
            playbackComparisonLadder(MusicSource.QQ, AudioQuality.HI_RES, AudioQuality.LOSSLESS),
        )
        assertEquals(
            listOf(AudioQuality.HI_RES),
            playbackComparisonLadder(MusicSource.QQ, AudioQuality.HI_RES, AudioQuality.HI_RES),
        )
        assertEquals(
            listOf(AudioQuality.DOLBY, AudioQuality.LOSSLESS, AudioQuality.EXHIGH),
            playbackComparisonLadder(MusicSource.QQ, AudioQuality.DOLBY, AudioQuality.EXHIGH),
        )
    }

    @Test fun parallelLanesOnlyExistForHiResAndDolby() {
        assertEquals(listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS), playbackParallelLanes(AudioQuality.HI_RES))
        assertEquals(listOf(AudioQuality.DOLBY, AudioQuality.LOSSLESS), playbackParallelLanes(AudioQuality.DOLBY))
        assertTrue(playbackParallelLanes(AudioQuality.EXHIGH).isEmpty())
        assertEquals(AudioQuality.HI_RES, playbackHigherQuality(AudioQuality.HI_RES, AudioQuality.LOSSLESS))
        assertEquals(AudioQuality.LOSSLESS, playbackHigherQuality(null, AudioQuality.LOSSLESS))
        assertNull(playbackHigherQuality(null, null))
    }
}
