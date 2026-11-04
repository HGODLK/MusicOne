package com.musicone

import org.junit.Assert.*
import org.junit.Test

class MainSourceOfflineFavoritesTest {
    @Test fun cachedAndEmptyFavoritesOpenWithoutFetchingRemoteDetails() {
        for (count in listOf(0, 28)) {
            val original = MusicPlaylist("favorites", MusicSource.QQ, "我喜欢", "", "", count, 1, 2, "喜", emptyList(),
                sourceAccountId = "account", remoteKey = "real-favorites")
            val offline = mainSourceOfflineFavorites(original)
            assertFalse(shouldCachePlaylistDetail(offline))
            assertEquals(original.id, offline.id)
            assertEquals(original.sourceAccountId, offline.sourceAccountId)
            assertEquals(original.remoteKey, offline.remoteKey)
            assertEquals(original.tracks, PlaylistPagingController { _, _ -> error("离线不能访问远端") }
                .entry(offline).page!!.playlist.tracks)
            assertTrue(offline.isPlatformFavorites)
        }
    }
}
