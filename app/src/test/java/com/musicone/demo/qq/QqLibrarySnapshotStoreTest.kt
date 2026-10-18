package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class QqLibrarySnapshotStoreTest {
    private val saved = mutableMapOf<String, List<MusicPlaylist>>()
    private val store = QqLibrarySnapshotStore({ saved[it].orEmpty() }, { key, value -> saved[key] = value })
    private fun session(id: String, credential: String = "ticket") = PlatformSession(
        MusicSource.QQ, credential, "device", MusicAccount(MusicSource.QQ, id, "昵称", null))
    private fun playlist(id: String) = MusicPlaylist(id, MusicSource.QQ, "歌单", "2 首歌曲", "简介",
        2, 1L, 2L, "歌", emptyList(), "https://example.com/cover.jpg", 301L, true)

    @Test fun renewedCredentialsRestoreTheSameAccountsDirectoryAndOwnership() {
        val original = playlist("qq-1")
        store.saveCreated(session("a", "old"), listOf(original))
        assertEquals(listOf(original), store.read(session("a", "new")).createdPlaylists)
    }

    @Test fun switchingAccountsCannotExposeThePreviousAccountsLibrary() {
        store.saveCreated(session("a"), listOf(playlist("qq-1")))
        val other = store.read(session("b"))
        assertTrue(other.createdPlaylists.isEmpty())
        assertTrue(other.collectedPlaylists.isEmpty())
        assertNull(other.favoritePlaylist)
    }

    @Test fun successfulEmptyRefreshClearsOnlyItsOwnSection() {
        val session = session("a")
        val original = playlist("qq-1")
        store.saveFavorites(session, original)
        store.saveCreated(session, listOf(original))
        store.saveCollected(session, listOf(original))
        store.saveCreated(session, emptyList())
        val restored = store.read(session)
        assertTrue(restored.createdPlaylists.isEmpty())
        assertEquals(original, restored.favoritePlaylist)
        assertEquals(listOf(original), restored.collectedPlaylists)
    }
}
