package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSourceLibraryPresentationTest {
    @Test
    fun artworkVersionOnlyAdvancesForChangedPlaylist() {
        val original = playlist("qq-123", "歌单")
        val changed = original.copy(count = 1)
        val detailsOnlyChanged = original.copy(
            description = "后台补全的详情",
            tracks = listOf(track("qq-song-1")),

        ).copy(providerData = mapOf("qqDirectoryId" to "123"))

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
    fun unchangedPlaylistReturnDoesNotForceArtworkRefresh() {
        assertTrue(forcedArtworkRefreshIds("qq-123", contentChanged = false).isEmpty())
        assertEquals(setOf("qq-123"), forcedArtworkRefreshIds("qq-123", contentChanged = true))
    }

    @Test
    fun playlistEditorKeepsTheSongCountSubtitleShownByTheLibraryCard() {
        val original = playlist("qq-123", "自己的歌单").copy(
            subtitle = "登录账号昵称",
            count = 18,

        ).copy(providerData = mapOf("qqDirectoryId" to "301"))

        val presented = original.withMainSourceLibrarySongCountSubtitle()

        assertEquals("18 首歌曲", presented.subtitle)
        assertEquals(original.id, presented.id)
        assertEquals(original.providerData, presented.providerData)
    }

    @Test
    fun clearingSettledFavoriteOverridesRestoresCloudTracksAndCount() {
        val snapshot = MainSourceFavoritePlaylistSnapshot()
        val song = track("qq-song")
        snapshot.record(playlist("qq-favorite", "我喜欢"))
        assertEquals(listOf(song), snapshot.present(mapOf(song.id to (song to true)))?.tracks)
        assertEquals(0, snapshot.present(emptyMap())?.count)
        assertTrue(requireNotNull(snapshot.present(emptyMap())).tracks.isEmpty())
        snapshot.record(playlist("qq-favorite", "我喜欢").copy(tracks = listOf(song), count = 1))
        assertEquals(0, snapshot.present(mapOf(song.id to (song to false)))?.count)
        assertEquals(listOf(song), snapshot.present(emptyMap())?.tracks)
        snapshot.clear()
        assertEquals(null, snapshot.present(emptyMap()))
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
