package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class QqLyricsPreloadPolicyTest {
    private val queue = (0..7).map { MusicTrack("$it", MusicSource.QQ, "曲", "歌手", "", 240_000, 0, 0, "", "") }
    private val state = MusicOneUiState(currentTrack = queue[0], queue = queue)

    @Test fun audioStillPrefetchesOneTrackWhileLyricsCoverFiveFollowingTracks() {
        assertEquals("1", nextQqPreloadTrack(state)?.id)
        assertEquals(listOf("1", "2", "3", "4", "5"), nextQqLyricsPreloadTracks(state).map { it.id })
        val shuffled = state.copy(shuffle = true, queue = listOf(queue[0], queue[6], queue[4], queue[2], queue[1], queue[7]))
        assertEquals(listOf("6", "4", "2", "1", "7"), nextQqLyricsPreloadTracks(shuffled).map { it.id })
    }

    @Test fun shortQueuesAndLoopingDoNotRepeatSongsOrIncludeTheCurrentTrack() {
        assertEquals(listOf("1", "2"), nextQqLyricsPreloadTracks(state.copy(queue = queue.take(3))).map { it.id })
        assertEquals(listOf("0", "1", "2", "3", "4"),
            nextQqLyricsPreloadTracks(state.copy(currentTrack = queue.last())).map { it.id })
        assertTrue(nextQqLyricsPreloadTracks(state.copy(queue = queue.take(1))).isEmpty())
        assertTrue(nextQqLyricsPreloadTracks(state.copy(repeatMode = RepeatMode.ONE)).isEmpty())
    }

    @Test fun radioAndStoppingAtTheEndDoNotWrapBackIntoHistory() {
        val nearEnd = state.copy(currentTrack = queue[6])
        assertEquals(listOf("7"), nextQqLyricsPreloadTracks(nearEnd.copy(qqRadioActive = true)).map { it.id })
        assertEquals(listOf("7"), nextQqLyricsPreloadTracks(nearEnd.copy(repeatMode = RepeatMode.OFF)).map { it.id })
        assertTrue(nextQqLyricsPreloadTracks(state.copy(currentTrack = queue.last(), repeatMode = RepeatMode.OFF)).isEmpty())
        assertTrue(nextQqLyricsPreloadTracks(state.copy(qualityChanging = true)).isEmpty())
    }
}
