package com.musicone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSourceRecentCachePolicyTest {
    @Test fun recentSongsAreEphemeralWhileServerCollectionsKeepTheirCachePolicy() {
        val song = MusicTrack("qq-song", MusicSource.QQ, "歌曲", "歌手", "专辑", 180_000, 0, 0, "", "")
        val collection = MusicPlaylist("qq-1", MusicSource.QQ, "歌单", "", "", 1, 0, 0, "", listOf(song))
        val snapshot = MainSourceRecentSnapshot(songs = listOf(song), songCount = 3, playlists = listOf(collection))
        assertFalse(shouldCachePlaylistDetail(snapshot.asRecentPlaylists().first()))
        assertTrue(shouldCachePlaylistDetail(snapshot.playlists.first()))
    }
}
