package com.musicone.source.netease

import org.junit.Assert.*
import org.junit.Test

class NeteasePlaylistDetailTest {
    private fun track(id: String) = NeteaseTrack(
        "netease-$id", NeteaseOrigin.NETEASE, "歌曲$id", "歌手", "专辑", 1_000,
        0xFF000000, 0xFF000000, "", "",
    )

    @Test fun missingIdsExcludeTracksAlreadyReturnedWithPlaylist() {
        assertEquals(listOf("1", "3"), missingPlaylistTrackIds(listOf("1", "2", "3"), listOf(track("2"))))
    }

    @Test fun loadedTracksAreRestoredToPlaylistOrder() {
        assertEquals(
            listOf("netease-1", "netease-2", "netease-3"),
            orderedPlaylistTracks(listOf("1", "2", "3"), listOf(track("3"), track("1"), track("2"))).map(NeteaseTrack::id),
        )
    }

}
