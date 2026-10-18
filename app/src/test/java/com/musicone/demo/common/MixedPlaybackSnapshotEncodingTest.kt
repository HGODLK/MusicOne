package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MixedPlaybackSnapshotEncodingTest {
    @Test fun explicitlyEmptyQueueReplacesTheOldSnapshotInsteadOfRevivingItOnRestore() {
        val song = MusicTrack("qq-1", MusicSource.QQ, "歌曲", "", "", 0, 0, 0, "", "")
        val playing = MusicOneUiState(currentTrack = song, queue = listOf(song))
        val encoding = MixedPlaybackSnapshotEncoding()
        assertNotNull(encoding.contentIfChanged(playing))
        val empty = JSONObject(encoding.contentIfChanged(MusicOneUiState())!!)
        assertEquals(1, empty.getInt("version"))
        assertTrue(empty.isNull("current"))
        assertEquals(0, empty.getJSONArray("queue").length())
        assertNull(encoding.contentIfChanged(MusicOneUiState()))
        assertNotNull(encoding.contentIfChanged(playing))
    }

    @Test fun largeMixedQueueIsCompleteAndProgressUpdatesReuseItsEncoding() {
        val tracks = (0 until 2_501).map {
            MusicTrack("track-$it", if (it % 2 == 0) MusicSource.LOCAL else MusicSource.QQ,
                "歌曲$it", "", "", 0, 0, 0, "", "")
        }
        val state = MusicOneUiState(currentTrack = tracks[1_500], queue = tracks,
            queueOrder = PlaybackQueueOrder(tracks.map { it.id }))
        val encoding = MixedPlaybackSnapshotEncoding()
        val first = JSONObject(encoding.contentIfChanged(state)!!)
        assertEquals(2_501, first.getJSONArray("queue").length())
        assertEquals(2_501, first.getJSONArray("order").length())
        repeat(100) { assertNull(encoding.contentIfChanged(state.copy(isPlaying = it % 2 == 0))) }
        val next = state.copy(currentTrack = tracks[1_501])
        assertEquals(tracks[1_501].id, JSONObject(encoding.contentIfChanged(next)!!).getString("current"))
    }

    @Test fun metadataAndModeChangesStillProduceANewSnapshot() {
        val song = MusicTrack("qq-1", MusicSource.QQ, "歌曲", "", "", 0, 0, 0, "", "")
        val state = MusicOneUiState(currentTrack = song, queue = listOf(song))
        val encoding = MixedPlaybackSnapshotEncoding()
        encoding.contentIfChanged(state)
        assertNotNull(encoding.contentIfChanged(state.copy(shuffle = true)))
        assertNotNull(encoding.contentIfChanged(state.copy(queue = listOf(song.copy(title = "已补全")))))
    }
}
