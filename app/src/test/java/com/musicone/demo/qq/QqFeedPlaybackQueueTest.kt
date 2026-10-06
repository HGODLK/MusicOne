package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class QqFeedPlaybackQueueTest {
    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, id, "歌手", "", 180000,
        0, 0, id, "")

    @Test fun feedClickKeepsPreviousAndAllRadioRecommendations() {
        val first = track("first")
        val second = track("second")
        val radio = (1..5).map { track("radio$it") }
        val window = QqRadioQueueWindow()
        val initial = window.insert(emptyList(), null, first)
        val filled = window.append(initial, radio, first.id)
        val next = window.insert(filled, first, second)
        assertEquals(first, previousQqRadioTrack(next, second.id))
        assertEquals(radio.first(), nextQqRadioTrack(next, second.id))
        assertEquals(listOf(first, second) + radio, next)
        assertTrue(window.seenIds().containsAll(listOf(first.id, second.id)))
    }

    @Test fun clickingAnExistingQueueSongMovesItImmediatelyAfterCurrent() {
        val tracks = (1..5).map { track("$it") }
        val result = insertQqFeedTrack(tracks, tracks[1], tracks[4])
        assertEquals(listOf("1", "2", "5", "3", "4"), result.map { it.id })
        assertEquals(5, result.distinctBy { it.id }.size)
    }
}
