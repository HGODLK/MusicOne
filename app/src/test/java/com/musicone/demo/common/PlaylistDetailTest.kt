package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaylistDetailTest {
    private fun track(id: String) = MusicTrack(
        "netease-$id", MusicSource.NETEASE, "歌曲$id", "歌手", "专辑", 1_000,
        0xFF000000, 0xFF000000, "", "",
    )

    @Test fun missingIdsExcludeTracksAlreadyReturnedWithPlaylist() {
        assertEquals(listOf("1", "3"), missingPlaylistTrackIds(listOf("1", "2", "3"), listOf(track("2"))))
    }

    @Test fun loadedTracksAreRestoredToPlaylistOrder() {
        assertEquals(
            listOf("netease-1", "netease-2", "netease-3"),
            orderedPlaylistTracks(listOf("1", "2", "3"), listOf(track("3"), track("1"), track("2"))).map(MusicTrack::id),
        )
    }

    @Test fun kugouPreviewTracksDoNotCountAsCompletePlaylist() {
        assertFalse(hasCompleteKugouTrackList(3, 20))
        assertTrue(hasCompleteKugouTrackList(20, 20))
    }

    @Test fun qqPlaylistDescriptionDecodesEmojiEntitiesAndHtmlBreaks() {
        assertEquals(
            "🎼 和你一起\n星河 & 晚风",
            decodeQqPlaylistDescription("&#127932;&#160;和你一起<br>星河 &amp; 晚风"),
        )
        assertEquals("~ 纯音 ❀ galgame", decodeQqPlaylistDescription("~ 纯音 &#10048; galgame"))
    }
}
