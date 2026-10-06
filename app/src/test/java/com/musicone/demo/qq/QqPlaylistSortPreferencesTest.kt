package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QqPlaylistSortPreferencesTest {
    @Test fun favoritesAreScopedToTheAccount() {
        val favorites = playlist(QQ_FAVORITES_PLAYLIST_ID)
        assertNotNull(qqPlaylistSortKey(favorites, "100", emptyList()))
        assertNotEquals(qqPlaylistSortKey(favorites, "100", emptyList()), qqPlaylistSortKey(favorites, "200", emptyList()))
        assertNull(qqPlaylistSortKey(favorites, null, emptyList()))
        assertNull(qqPlaylistSortKey(favorites, " ", emptyList()))
    }

    @Test fun createdPlaylistsHaveIndependentPreferences() {
        val first = playlist("qq-1001")
        val second = playlist("qq-1002")
        val created = listOf(first, second)
        assertNotNull(qqPlaylistSortKey(first, "100", created))
        assertNotEquals(qqPlaylistSortKey(first, "100", created), qqPlaylistSortKey(second, "100", created))
        assertNotEquals(qqPlaylistSortKey(first, "100", created), qqPlaylistSortKey(first, "200", created))
    }

    @Test fun directoryAndPublicEntryUseTheSameOwnedPlaylistPreference() {
        val directory = playlist("${QQ_PROFILE_DIRECTORY_ID_PREFIX}88")
        val public = playlist("qq-9988").copy(qqDirectoryId = 88)
        assertEquals(qqPlaylistSortKey(directory, "100", emptyList()), qqPlaylistSortKey(public, "100", listOf(public)))
        assertEquals(qqPlaylistSortKey(public, "100", listOf(public)),
            qqPlaylistSortKey(public.copy(title = "改名"), "100", listOf(public)))
    }

    @Test fun otherPlaylistsCannotReadOrWriteOwnedPreferences() {
        listOf("qq-777", "qq-daily-100", QQ_RECENT_SONGS_PLAYLIST_ID).forEach { id ->
            assertNull(qqPlaylistSortKey(playlist(id), "100", emptyList()))
        }
        assertNull(qqPlaylistSortKey(playlist("qq-777").copy(title = "我喜欢的音乐", isOwned = true), "100", emptyList()))
        MusicSource.entries.filter { it != MusicSource.QQ }.forEach { source ->
            val other = playlist(QQ_FAVORITES_PLAYLIST_ID).copy(source = source)
            assertNull(qqPlaylistSortKey(other, "100", listOf(other)))
        }
    }

    @Test fun storedSortRestoresAndUnknownValuesFallBack() {
        PlaylistSort.entries.forEach { assertEquals(it, playlistSortFromPreference(it.name)) }
        assertEquals(PlaylistSort.ADDED_DESC, playlistSortFromPreference(null))
        assertEquals(PlaylistSort.ADDED_DESC, playlistSortFromPreference("removed-mode"))
    }

    private fun playlist(id: String) = MusicPlaylist(
        id, MusicSource.QQ, "歌单", "", "", 0, 0L, 0L, "", emptyList(),
    )
}
