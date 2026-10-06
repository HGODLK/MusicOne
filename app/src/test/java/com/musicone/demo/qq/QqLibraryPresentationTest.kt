package com.musicone.demo

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
    fun artworkVersionOnlyAdvancesForChangedPlaylist() {
        val original = playlist("qq-123", "歌单")
        val changed = original.copy(count = 1)
        val detailsOnlyChanged = original.copy(
            description = "后台补全的详情",
            tracks = listOf(track("qq-song-1")),
            qqDirectoryId = 123L,
        )

        val unchangedVersions = refreshedArtworkVersions(
            current = mapOf(original.id to 3L),
            previous = listOf(original),
            refreshed = listOf(original),
        )
        val initialVersions = refreshedArtworkVersions(
            current = emptyMap(),
            previous = emptyList(),
            refreshed = listOf(original),
        )
        val detailsOnlyVersions = refreshedArtworkVersions(
            current = unchangedVersions,
            previous = listOf(original),
            refreshed = listOf(detailsOnlyChanged),
        )
        val changedVersions = refreshedArtworkVersions(
            current = unchangedVersions,
            previous = listOf(original),
            refreshed = listOf(changed),
        )
        val forcedVersions = refreshedArtworkVersions(
            current = unchangedVersions,
            previous = listOf(original),
            refreshed = listOf(original),
            forceRefreshIds = setOf(original.id),
        )

        assertEquals(3L, unchangedVersions[original.id])
        assertTrue(initialVersions.isEmpty())
        assertEquals(3L, detailsOnlyVersions[original.id])
        assertTrue(original.hasSameLibraryCardPresentation(detailsOnlyChanged))
        assertFalse(original.hasSameLibraryCardPresentation(changed))
        assertEquals(4L, changedVersions[original.id])
        assertEquals(4L, forcedVersions[original.id])
    }

    @Test
    fun emptyPlaylistKeepsEntryArtworkInsteadOfQqDefaultCover() {
        val fallback = "https://example.com/local-fallback.jpg"
        val qqDefault = "https://example.com/qq-empty.jpg"

        assertEquals(fallback, qqPlaylistDetailArtwork(fallback, qqDefault, hasTracks = false))
        assertEquals(null, qqPlaylistDetailArtwork(null, qqDefault, hasTracks = false))
        assertEquals(qqDefault, qqPlaylistDetailArtwork(fallback, qqDefault, hasTracks = true))
    }

    @Test
    fun unchangedPlaylistReturnDoesNotForceArtworkRefresh() {
        assertTrue(forcedArtworkRefreshIds("qq-123", contentChanged = false).isEmpty())
        assertEquals(setOf("qq-123"), forcedArtworkRefreshIds("qq-123", contentChanged = true))
    }

    @Test
    fun playlistEditorKeepsTheSongCountSubtitleShownByTheLibraryCard() {
        val original = playlist("qq-123", "自己的歌单").copy(
            subtitle = "登录账号昵称",
            count = 18,
            qqDirectoryId = 301L,
        )

        val presented = original.withQqLibrarySongCountSubtitle()

        assertEquals("18 首歌曲", presented.subtitle)
        assertEquals(original.id, presented.id)
        assertEquals(original.qqDirectoryId, presented.qqDirectoryId)
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
