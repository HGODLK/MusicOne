package com.musicone.demo

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueLimitTest {
    private val tracks = (0..MAX_PLAYBACK_QUEUE_SIZE).map {
        MusicTrack("qq-$it", MusicSource.QQ, "歌曲$it", "歌手", "", 180_000, 0, 0, "", "")
    }

    @Test fun playlistQueueStopsAt99999AndKeepsExplicitlySelectedTrackThroughShuffle() {
        val selected = tracks.last()
        val state = MusicOneUiState().withPlaylistQueue(tracks, true, Random(9), selectedTrack = selected)
        assertEquals(99_999, state.queue.size)
        assertTrue(selected in state.queue)
        assertEquals(99_999, state.queueOrder.originalIds.size)
        assertEquals(tracks.take(99_998) + selected, state.withPlaybackMode(PlayerPlaybackMode.LIST).queue)
        assertEquals(tracks.take(99_999), MusicOneUiState().withPlaylistQueue(tracks, false).queue)
    }

    @Test fun playingANewTrackDoesNotTruncateALargeQueueTo1000() {
        val queue = tracks.take(1_462)
        assertEquals(queue + tracks.last(), appendPlaybackTrack(queue, tracks.last()))
        val full = tracks.take(MAX_PLAYBACK_QUEUE_SIZE)
        val appended = appendPlaybackTrack(full, tracks.last())
        assertEquals(99_999, appended.size)
        assertEquals(tracks.last(), appended.last())
        assertSame(full, appendPlaybackTrack(full, full.last()))
    }

    @Test fun insertingAfterTheLastTrackAtCapacityPreservesCurrentAndSelected() {
        val full = tracks.take(MAX_PLAYBACK_QUEUE_SIZE)
        val current = full.last()
        val state = MusicOneUiState(currentTrack = current, queue = full,
            queueOrder = PlaybackQueueOrder(full.map { it.id })).withTrackInsertedNext(tracks.last())
        assertEquals(99_999, state.queue.size)
        assertEquals(listOf(current, tracks.last()), state.queue.takeLast(2))
        assertEquals(current, state.currentTrack)
        assertEquals(state.queue.map { it.id }, state.queueOrder.originalIds)
        assertEquals(99_998, state.insertedNextIndex())
    }

    @Test fun restoringAnOlderOversizedQueueKeepsItsCurrentTrackAndValidOrder() {
        val current = tracks.last()
        val snapshot = QqPlaybackSnapshot(tracks, current.id, 0, false, RepeatMode.ALL,
            originalQueueIds = tracks.map { it.id })
        val restored = MusicOneUiState().withQqPlaybackSnapshot(snapshot)
        assertEquals(99_999, restored.queue.size)
        assertEquals(current, restored.currentTrack)
        assertEquals(restored.queue.map { it.id }, restored.queueOrder.originalIds)
    }

    @Test fun insertingIntoAFullShuffledQueuePreservesTheOriginalOrderOfSurvivors() {
        val full = tracks.take(MAX_PLAYBACK_QUEUE_SIZE)
        val shuffled = full.shuffled(Random(4))
        val current = shuffled[20]
        val state = MusicOneUiState(currentTrack = current, queue = shuffled, shuffle = true,
            queueOrder = PlaybackQueueOrder(full.map { it.id })).withTrackInsertedNext(tracks.last())
        val survivorIds = state.queue.mapTo(hashSetOf()) { it.id }
        val expected = full.filter { it.id in survivorIds }.toMutableList()
        expected.add(expected.indexOf(current) + 1, tracks.last())
        assertEquals(99_999, state.queue.size)
        assertEquals(expected, state.withPlaybackMode(PlayerPlaybackMode.LIST).queue)
    }
}
