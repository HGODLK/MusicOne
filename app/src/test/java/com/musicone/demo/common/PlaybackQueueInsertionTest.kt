package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueInsertionTest {
    private val tracks = (1..4).map { track("qq-$it") }

    @Test fun insertNextKeepsCurrentTrackPlaybackIntentAndTheRestOfTheQueue() {
        for (source in MusicSource.entries) for (playing in listOf(false, true)) {
            val selected = track("${source.name}-added", source)
            val before = MusicOneUiState(currentTrack = tracks[1], queue = tracks,
                isPlaying = playing, playerExpanded = true, activeQuality = AudioQuality.LOSSLESS)
            val after = before.withTrackInsertedNext(selected)
            assertEquals(listOf(tracks[0], tracks[1], selected, tracks[2], tracks[3]), after.queue)
            assertSame(before.currentTrack, after.currentTrack)
            assertEquals(playing, after.isPlaying)
            assertEquals(before.activeQuality, after.activeQuality)
            assertEquals(before.playerExpanded, after.playerExpanded)
        }
    }

    @Test fun selectingAnExistingSongMovesItWithoutDuplicatingOrDroppingOtherSongs() {
        val before = MusicOneUiState(currentTrack = tracks[1], queue = tracks)
        val after = before.withTrackInsertedNext(tracks[3]).withTrackInsertedNext(tracks[3])
        assertEquals(listOf(tracks[0], tracks[1], tracks[3], tracks[2]), after.queue)
        assertSame(before, before.withTrackInsertedNext(tracks[1]))
    }

    @Test fun explicitNextOverridesSingleRepeatOnceWithoutChangingTheSelectedMode() {
        for (repeat in RepeatMode.entries) for (shuffle in listOf(false, true)) {
            val before = MusicOneUiState(currentTrack = tracks[1], queue = tracks,
                repeatMode = repeat, shuffle = shuffle)
            val after = before.withTrackInsertedNext(tracks[3])
            assertEquals(tracks[3], after.queue[after.insertedNextIndex()!!])
            assertEquals(repeat, after.repeatMode)
            assertEquals(shuffle, after.shuffle)
            val started = after.forTrackTransition(tracks[3], playWhenReady = true)
            assertNull(started.insertedNextIndex())
            assertEquals(repeat, started.repeatMode)
            assertNull(started.forTrackTransition(tracks[1], playWhenReady = true).insertedNextIndex())
        }
    }

    @Test fun insertedSongsSurviveRestoringShuffleOrderAndCanFollowTheLastSong() {
        val selected = track("qq-added")
        val before = MusicOneUiState(currentTrack = tracks[1], queue = tracks.reversed(), shuffle = true,
            queueOrder = PlaybackQueueOrder(tracks.map { it.id }))
        val after = before.withTrackInsertedNext(selected).withPlaybackMode(PlayerPlaybackMode.LIST)
        assertEquals(listOf(tracks[0], tracks[1], selected, tracks[2], tracks[3]), after.queue)
        val last = MusicOneUiState(currentTrack = tracks.last(), queue = tracks, repeatMode = RepeatMode.OFF)
            .withTrackInsertedNext(selected)
        assertEquals(last.queue.lastIndex, last.insertedNextIndex())
        assertEquals(selected, last.queue.last())
    }

    @Test fun otherSourceInsertionLeavesRadioWhileQqInsertionKeepsItsSession() {
        val before = MusicOneUiState(currentTrack = tracks[1], queue = tracks, qqRadioActive = true)
        assertTrue(before.withTrackInsertedNext(tracks[3]).qqRadioActive)
        val mixed = before.withTrackInsertedNext(track("local-added", MusicSource.LOCAL))
        assertFalse(mixed.qqRadioActive)
        assertSame(before.currentTrack, mixed.currentTrack)
    }

    private fun track(id: String, source: MusicSource = MusicSource.QQ) =
        MusicTrack(id, source, "歌曲", "歌手", "", 240_000, 0, 0, "", "")
}
