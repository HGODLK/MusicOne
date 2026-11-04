package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QqPlaylistSortPreferencesTest {
    @Test fun favoritesAreScopedToTheAccount() {
        val favorites = playlist(QQ_FAVORITES_PLAYLIST_ID)
        assertNotNull(qqPlaylistSortKey(favorites, "100"))
        assertNotEquals(qqPlaylistSortKey(favorites, "100"), qqPlaylistSortKey(favorites, "200"))
        assertNull(qqPlaylistSortKey(favorites, null))
        assertNull(qqPlaylistSortKey(favorites, " "))
    }

    @Test fun createdPlaylistsHaveIndependentPreferences() {
        val first = playlist("qq-1001").withQqDirectoryId(301)
        val second = playlist("qq-1002").withQqDirectoryId(302)
        val created = listOf(first, second)
        assertNotNull(qqPlaylistSortKey(first, "100"))
        assertNotEquals(qqPlaylistSortKey(first, "100"), qqPlaylistSortKey(second, "100"))
        assertNotEquals(qqPlaylistSortKey(first, "100"), qqPlaylistSortKey(first, "200"))
    }

    @Test fun directoryAndPublicEntryUseTheSameOwnedPlaylistPreference() {
        val directory = playlist("${QQ_PROFILE_DIRECTORY_ID_PREFIX}88")
        val public = playlist("qq-9988").withQqDirectoryId(88)
        assertEquals(qqPlaylistSortKey(directory, "100"), qqPlaylistSortKey(public, "100"))
        assertEquals(qqPlaylistSortKey(public, "100"),
            qqPlaylistSortKey(public.copy(title = "改名"), "100"))
    }

    @Test fun otherPlaylistsCannotReadOrWriteOwnedPreferences() {
        listOf("qq-0", "qq-invalid", "qq-daily-100", QQ_RECENT_SONGS_PLAYLIST_ID).forEach { id ->
            assertNull(qqPlaylistSortKey(playlist(id), "100"))
        }
        assertNotNull(qqPlaylistSortKey(playlist("qq-777").copy(title = "我喜欢的音乐", isOwned = true), "100"))
        MusicSource.entries.filter { it != MusicSource.QQ }.forEach { source ->
            val other = playlist(QQ_FAVORITES_PLAYLIST_ID).copy(source = source)
            assertNull(qqPlaylistSortKey(other, "100"))
        }
    }

    @Test fun storedSortRestoresAndUnknownValuesFallBack() {
        listOf("ADDED_DESC", "ADDED_ASC", "NAME").forEach { assertEquals(it, qqPlaylistSortId(it)) }
        assertEquals("ADDED_DESC", qqPlaylistSortId(null))
        assertEquals("ADDED_DESC", qqPlaylistSortId("removed-mode"))
    }

    @Test fun recommendedAndSavedPublicPlaylistsHaveStableIndependentAccountPreferences() {
        val recommended = playlist("qq-777")
        assertNotNull(qqPlaylistSortKey(recommended, "100"))
        assertEquals(qqPlaylistSortKey(recommended, "100"), qqPlaylistSortKey(recommended.copy(title = "收藏后改名"), "100"))
        assertNotEquals(qqPlaylistSortKey(recommended, "100"), qqPlaylistSortKey(recommended, "200"))
        assertNotEquals(qqPlaylistSortKey(recommended, "100"), qqPlaylistSortKey(playlist("qq-778"), "100"))
        assertNotEquals(qqPlaylistSortKey(recommended, "100"), qqPlaylistSortKey(playlist("qq-777").withQqDirectoryId(777), "100"))
    }

    private fun playlist(id: String) = MusicPlaylist(
        id, MusicSource.QQ, "歌单", "", "", 0, 0L, 0L, "", emptyList(),
    )
}
