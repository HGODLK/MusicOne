package com.musicone.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLaunchOverlayTest {
    @Test
    fun launchWaitsForSessionAndBothHomeRequestsToSettle() {
        val settled = MusicCatalogUiState(
            source = MusicSource.QQ,
            sessionRevision = 7L,
            recommendationsSettled = true,
            recommendedTracksSettled = true,
        )

        assertFalse(homeLaunchMetadataReady(MusicSource.QQ, 7L, SessionStatus.CHECKING, settled))
        assertFalse(homeLaunchMetadataReady(
            MusicSource.QQ,
            7L,
            SessionStatus.CONNECTED,
            settled.copy(recommendedTracksSettled = false),
        ))
        assertFalse(homeLaunchMetadataReady(MusicSource.QQ, 8L, SessionStatus.CONNECTED, settled))
        assertTrue(homeLaunchMetadataReady(MusicSource.QQ, 7L, SessionStatus.CONNECTED, settled))
        assertTrue(homeLaunchMetadataReady(MusicSource.QQ, 7L, SessionStatus.CONNECTED,
            settled.copy(recommendationsSettled = false)))
        assertFalse(homeLaunchMetadataReady(MusicSource.NETEASE, 7L, SessionStatus.CONNECTED,
            settled.copy(source = MusicSource.NETEASE, recommendationsSettled = false)))
        assertTrue(homeLaunchMetadataReady(MusicSource.QQ, 7L, SessionStatus.SIGNED_OUT, settled))
    }

    @Test
    fun launchOnlyWaitsForArtworkVisibleNearTheTopOfEachHomeLayout() {
        val tracks = listOf(track("track-cover"))
        val playlists = listOf(playlist("first-cover"), playlist("second-cover"), playlist("below-fold"))
        val catalog = MusicCatalogUiState(recommendations = playlists, recommendedTracks = tracks)

        assertTrue(homeLaunchArtworkUrls(MusicSource.QQ, catalog) == listOf("track-cover"))
        assertTrue(homeLaunchArtworkUrls(MusicSource.NETEASE, catalog) == listOf("first-cover", "second-cover"))
    }

    private fun track(artworkUrl: String) = MusicTrack(
        id = artworkUrl,
        source = MusicSource.QQ,
        title = "歌曲",
        artists = "歌手",
        album = "专辑",
        durationMs = 0L,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = "歌",
        previewUrl = "",
        artworkUrl = artworkUrl,
    )

    private fun playlist(artworkUrl: String) = MusicPlaylist(
        id = artworkUrl,
        source = MusicSource.NETEASE,
        title = "歌单",
        subtitle = "",
        description = "",
        count = 0,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = "歌",
        tracks = emptyList(),
        artworkUrl = artworkUrl,
    )
}
