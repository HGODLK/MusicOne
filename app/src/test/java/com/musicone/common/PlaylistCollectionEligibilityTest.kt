package com.musicone

import org.junit.Assert.*
import org.junit.Test

class PlaylistCollectionEligibilityTest {
    private val playlist = MusicPlaylist("qq-test", MusicSource.QQ, "每日推荐", "", "", 0,
        0L, 0L, "", emptyList())

    @Test fun onlySemanticVirtualPlaylistsAreExcluded() {
        assertTrue(playlist.canCollectAsPlaylist())
        assertFalse(playlist.copy(providerData = mapOf("templateEphemeral" to "true")).canCollectAsPlaylist())
        assertFalse(playlist.copy(isPlatformFavorites = true).canCollectAsPlaylist())
        assertTrue(playlist.copy(source = MusicSource.KUGOU).canCollectAsPlaylist())
    }
}
