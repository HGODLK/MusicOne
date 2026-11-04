package com.musicone.source.kugou

import org.junit.Assert.*
import org.junit.Test

class KugouRecommendationPagingTest {
    private fun track(id: Int) = MusicTrack("kugou-$id", MusicSource.KUGOU, "$id", "歌手", "", 90_000, 0, 0, "", "")
    private fun window(total: Int, batch: Int, requested: MutableList<Int>): List<MusicTrack> =
        collectKugouRecommendationWindow(12, batch) { cursor ->
            val offset = cursor?.offset ?: 0
            requested += offset
            val end = minOf(offset + 100, total)
            PlaylistTrackPage(MusicPlaylist("list", title = "推荐", count = total,
                tracks = (offset until end).map(::track)), nextPlaylistTrackCursor(offset, end - offset, total))
        }
    @Test fun startupOnlyReadsTheFirstPageOfALargePlaylist() {
        val requested = mutableListOf<Int>()
        assertEquals((0 until 12).map(::track), window(650, 0, requested))
        assertEquals(listOf(0), requested)
    }
    @Test fun laterWindowReadsOnlyThePagesItNeeds() {
        val requested = mutableListOf<Int>()
        assertEquals((96 until 108).map(::track), window(650, 8, requested))
        assertEquals(listOf(0, 100), requested)
    }
    @Test fun wrappingAndShortPoolsKeepTheExistingBatchOrder() {
        for (total in listOf(6, 12, 30, 250)) for (batch in listOf(0, 1, 2, 20, -1)) {
            assertEquals(recommendationWindow((0 until total).map(::track), 12, batch), window(total, batch, mutableListOf()))
        }
    }
    @Test fun unknownTotalStillLoadsToTheEndBeforeChoosingAWindow() {
        val requested = mutableListOf<Int>()
        val result = collectKugouRecommendationWindow(12, 8) { cursor ->
            val offset = cursor?.offset ?: 0
            requested += offset
            val end = minOf(offset + 100, 150)
            PlaylistTrackPage(MusicPlaylist("list", title = "推荐", tracks = (offset until end).map(::track)),
                if (end < 150) PlaylistTrackCursor(end) else null)
        }
        assertEquals((96 until 108).map(::track), result)
        assertEquals(listOf(0, 100), requested)
    }
}
