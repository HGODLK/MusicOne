package com.musicone

import org.junit.Assert.*
import org.junit.Test

class PlaylistStatusArtworkTest {
    private val cover = "https://example.com/cover.jpg"
    private val track = MusicTrack("qq-song", MusicSource.QQ, "歌曲", "歌手", "专辑", 1000,
        0L, 0L, "", "", artworkUrl = "https://example.com/song.jpg")
    private val playlist = MusicPlaylist("qq-list", MusicSource.QQ, "歌单", "", "", 1,
        0L, 0L, "", emptyList(), artworkUrl = cover)

    @Test fun loadingTracksAndPresetColorsDoNotChangeStatusArtwork() {
        assertEquals(cover, playlistStatusArtwork(playlist))
        assertEquals(cover, playlistStatusArtwork(playlist.copy(tracks = listOf(track), artworkStart = 0xFFFF0000)))
        assertEquals(cover, playlistStatusArtwork(playlist.copy(tracks = listOf(track).reversed())))
    }

    @Test fun missingCoverDoesNotBorrowCurrentFirstSong() {
        assertNull(playlistStatusArtwork(playlist.copy(artworkUrl = null, tracks = listOf(track))))
        assertNull(playlistStatusArtwork(playlist.copy(artworkUrl = " ")))
        assertNull(playlistStatusArtwork(null))
    }
}
