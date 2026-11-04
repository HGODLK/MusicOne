package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.junit.Assert.*
import org.junit.Test

class KugouHomeArtworkPreparationTest {
    private fun track(id: String, url: String?) = SourceTrackDetails(
        SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, "42", id), id, artworkUrl = url), id)

    @Test fun preparesActualCardCoversAndAllHomeShelvesWithoutExpandingRecentHistory() {
        val daily = track("daily", "daily-cover")
        val recent = track("recent", "recent-cover")
        val song = track("song", "song-cover")
        val collection = SourceCollection(TrackIdentity(KUGOU_SOURCE_ID, "42", "list"), "list", "歌单",
            artwork = SourceArtwork("list-cover"))
        val state = KugouHomeState(daily = listOf(daily, track("daily-2", "unused-daily-cover")),
            recent = SourceRecentSnapshot(tracks = listOf(recent, track("recent-2", "unused-recent-cover"))),
            homeSections = listOf(SourceHomeSection("home", "首页", tracks = listOf(song, daily), collections = listOf(collection))))
        assertEquals(listOf("daily-cover", "recent-cover", "song-cover", "list-cover"),
            kugouHomeCachedArtworks(state).map { it.url })
    }

    @Test fun emptySnapshotsAndMissingArtworkRequireNoImageRead() {
        assertTrue(kugouHomeCachedArtworks(KugouHomeState()).isEmpty())
        assertTrue(kugouHomeCachedArtworks(KugouHomeState(daily = listOf(track("missing", null)))).isEmpty())
    }
}
