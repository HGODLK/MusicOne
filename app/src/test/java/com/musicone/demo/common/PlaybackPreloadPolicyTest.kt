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
    @Test fun staleAndFutureTicketsAreRejected() {
        assertTrue(preloadedPlaybackFresh(1_000L, 30_999L))
        assertFalse(preloadedPlaybackFresh(1_000L, 31_000L))
        assertFalse(preloadedPlaybackFresh(1_000L, 999L))
    }
    @Test fun foregroundPlaybackMustStartAndBuildBufferBeforePreloading() {
        assertFalse(playbackPreloadCanRun(false, 60_000L, 180_000L, false))
        assertFalse(playbackPreloadCanRun(true, 9_999L, 180_000L, false))
        assertTrue(playbackPreloadCanRun(true, 10_000L, 180_000L, false))
        assertFalse(playbackPreloadCanRun(false, 60_000L, 180_000L, true))
    }
    @Test fun runningDownloadsYieldAtLowBufferAndWaitForRecovery() {
        assertTrue(playbackPreloadCanRun(true, 3_000L, 180_000L, true))
        assertFalse(playbackPreloadCanRun(true, 2_999L, 180_000L, true))
        assertFalse(playbackPreloadCanRun(true, 5_000L, 180_000L, false))
        assertTrue(playbackPreloadCanRun(true, 10_000L, 180_000L, false))
    }
    @Test fun shortAndLocalTracksDoNotWaitForUnreachableBuffer() {
        assertTrue(playbackPreloadCanRun(true, 2_000L, 2_000L, false))
        assertFalse(playbackPreloadCanRun(true, 1_999L, 2_000L, false))
        assertTrue(playbackPreloadCanRun(true, 0L, 180_000L, false, local = true))
        assertFalse(playbackPreloadCanRun(false, 0L, 180_000L, false, local = true))
        assertFalse(playbackPreloadCanRun(true, 9_999L, null, false))
        assertTrue(playbackPreloadCanRun(true, 10_000L, null, false))
    }
    @Test fun ticketsAreRefreshedBeforeExpiryOnlyInTheEndWindow() {
        assertFalse(preloadedPlaybackRefreshDue(1_000L, 25_999L))
        assertTrue(preloadedPlaybackRefreshDue(1_000L, 26_000L))
        assertFalse(playbackPreloadNearEnd(null))
        assertFalse(playbackPreloadNearEnd(0L))
        assertFalse(playbackPreloadNearEnd(-1L))
        assertFalse(playbackPreloadNearEnd(30_001L))
        assertTrue(playbackPreloadNearEnd(30_000L))
        assertTrue(playbackPreloadNearEnd(1L))
    }
}
