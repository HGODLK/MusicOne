package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlaybackQueueToolsTest {
    @Test fun snapshotKeepsQueueOrderAndPlaybackPosition() {
        val first = track("一")
        val current = track("二")
        val state = MusicOneUiState(
            currentTrack = current,
            queue = listOf(first, current, first),
            isPlaying = true,
            shuffle = true,
            repeatMode = RepeatMode.ONE,
            radioActive = true,
            radioLoading = false,
        )

        val snapshot = aiPlaybackQueueSnapshot(state, capturedAt = 123L)

        assertEquals(listOf(first, current, first), snapshot.tracks)
        assertEquals(123L, snapshot.playback.getLong("capturedAt"))
        assertTrue(snapshot.playback.getBoolean("isPlaying"))
        assertEquals(2, snapshot.playback.getInt("currentPosition"))
        assertEquals("ONE", snapshot.playback.getString("repeatMode"))
        assertTrue(snapshot.playback.getBoolean("shuffle"))
        assertTrue(snapshot.playback.getBoolean("radioActive"))
        assertFalse(snapshot.playback.getBoolean("radioLoading"))
    }

    private fun track(title: String) = MusicTrack(title, MusicSource.LOCAL, title, "歌手", "专辑", 1000,
        0, 0, "", "")
}
