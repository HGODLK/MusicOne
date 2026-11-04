package com.musicone.source.qq.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqLibraryPresentationTest {
    @Test
    fun favoritesShortcutIsRemovedFromCreatedPlaylists() {
        assertTrue(playlist("qq-profile:dir:201", "我喜欢").isQqFavoritesShortcut())
        assertTrue(playlist("qq-1", "我 喜欢的音乐").isQqFavoritesShortcut())
        assertFalse(playlist("qq-2", "我的私藏").isQqFavoritesShortcut())
    }

    @Test
    fun systemManagedDirectoriesAreRemovedFromCreatedPlaylists() {
        assertTrue(playlist("qq-1", "QZone背景音乐").isQqHiddenCreatedPlaylist())
        assertTrue(playlist("qq-2", "QZone 背景音乐").isQqHiddenCreatedPlaylist())
        assertTrue(playlist("qq-3", "本地上传").isQqHiddenCreatedPlaylist())
        assertFalse(playlist("qq-4", "我的本地收藏").isQqHiddenCreatedPlaylist())
    }

    @Test
    fun ownQqPlaylistsNeverExposeTheCollectionAction() {
        val created = playlist("qq-123", "自己的歌单")
        assertTrue(created.isOwnedQqPlaylist(listOf(created)))
        assertTrue(playlist("qq-profile:dir:201", "自建目录").isOwnedQqPlaylist(emptyList()))
        assertTrue(playlist("qq-daily-2026-09-11", "每日推荐").isOwnedQqPlaylist(emptyList()))
        assertFalse(playlist("qq-456", "别人的歌单").isOwnedQqPlaylist(listOf(created)))
    }

    @Test
    fun emptyPlaylistKeepsEntryArtworkInsteadOfQqDefaultCover() {
        val fallback = "https://example.com/local-fallback.jpg"
        val qqDefault = "https://example.com/qq-empty.jpg"

        assertEquals(fallback, qqPlaylistDetailArtwork(fallback, qqDefault, hasTracks = false))
        assertEquals(null, qqPlaylistDetailArtwork(null, qqDefault, hasTracks = false))
        assertEquals(qqDefault, qqPlaylistDetailArtwork(fallback, qqDefault, hasTracks = true))
    }

    private fun playlist(id: String, title: String) = MusicPlaylist(
        id = id,
        source = MusicSource.QQ,
        title = title,
        subtitle = "QQ 音乐",
        description = "",
        count = 0,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = "歌",
        tracks = emptyList(),
    )

    private fun track(id: String) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = "歌曲",
        artists = "歌手",
        album = "专辑",
        durationMs = 180_000L,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = "歌",
        previewUrl = "",
    )
}
