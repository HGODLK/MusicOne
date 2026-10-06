package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqPlaylistCollectionTest {
    @Test fun collectionUsesTheOfficialPlaylistFavoriteModule() {
        val request = qqPlaylistCollectionCall(9988L, true)
        assertEquals("music.musicasset.PlaylistFavWrite", request.module)
        assertEquals("FavPlaylist", request.method)
        assertEquals(listOf(9988L), request.playlistIds)
    }

    @Test fun cancellationUsesTheOfficialCancelMethod() {
        assertEquals("CancelFavPlaylist", qqPlaylistCollectionCall(9988L, false).method)
    }

    @Test fun responseRequiresBothSuccessAndNoFailedPlaylistId() {
        assertTrue(qqPlaylistCollectionSucceeded(0, 0, 0, false))
        assertFalse(qqPlaylistCollectionSucceeded(-1, 0, 0, false))
        assertFalse(qqPlaylistCollectionSucceeded(0, -1, 0, false))
        assertFalse(qqPlaylistCollectionSucceeded(0, 0, -1, false))
        assertFalse(qqPlaylistCollectionSucceeded(0, 0, 0, true))
    }
}
