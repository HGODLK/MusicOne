package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class QqNextAudioPreloadPolicyTest {
    private val tracks = listOf("a", "b", "c").map { id ->
        MusicTrack(id, MusicSource.QQ, id, "歌手", "", 180_000L, 0, 0, id, "")
    }
    private val state = MusicOneUiState(currentTrack = tracks[0], queue = tracks)

    @Test fun preloadFollowsVisibleShuffleOrderAndSkipsSingleRepeat() {
        assertEquals("b", nextQqPreloadTrack(state)?.id)
        assertEquals("c", nextQqPreloadTrack(state.copy(shuffle = true,
            queue = listOf(tracks[0], tracks[2], tracks[1])))?.id)
        assertNull(nextQqPreloadTrack(state.copy(repeatMode = RepeatMode.ONE)))
        assertNull(nextQqPreloadTrack(state.copy(queue = listOf(tracks[0]))))
    }

    @Test fun endOfQueueAndQualityChangesDoNotFetchAnUnwantedTrack() {
        val last = state.copy(currentTrack = tracks.last())
        assertEquals("a", nextQqPreloadTrack(last)?.id)
        assertNull(nextQqPreloadTrack(last.copy(repeatMode = RepeatMode.OFF)))
        assertNull(nextQqPreloadTrack(state.copy(qualityChanging = true)))
        assertNull(nextQqPreloadTrack(state.copy(currentTrack = tracks[0].copy(source = MusicSource.KUGOU))))
    }

    @Test fun currentPlaybackHasPriorityButFullyBufferedEndingCanPreload() {
        assertFalse(canPreloadQqAudio(true, 9_000, 60_000))
        assertFalse(canPreloadQqAudio(false, 30_000, 60_000))
        assertTrue(canPreloadQqAudio(true, 10_000, 60_000))
        assertTrue(canPreloadQqAudio(true, 4_900, 5_000))
        assertFalse(canPreloadQqAudio(true, 2_000, 5_000))
    }

    @Test fun highResolutionBytesAreBoundedAndSourceTicketsExpire() {
        assertTrue(qqAudioPreloadBytes(320_000) < qqAudioPreloadBytes(1_999_000))
        assertEquals(8 * 1024 * 1024L, qqAudioPreloadBytes(Int.MAX_VALUE))
        assertTrue(qqAudioPreloadBytes(0) > 0)
        assertTrue(qqPreloadedSourceFresh(1_000, 30_999))
        assertFalse(qqPreloadedSourceFresh(1_000, 31_000))
        assertFalse(qqPreloadedSourceFresh(1_000, 999))
    }
}
