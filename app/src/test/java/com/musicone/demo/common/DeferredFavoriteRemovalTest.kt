package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredFavoriteRemovalTest {
    @Test
    fun stagedRemovalOnlyChangesLocalPresentation() {
        val track = MusicTrack(
            id = "qq-song",
            source = MusicSource.QQ,
            title = "歌",
            artists = "歌手",
            album = "专辑",
            durationMs = 180_000L,
            artworkStart = 0L,
            artworkEnd = 0L,
            artworkMark = "",
            previewUrl = "",
        )
        val staged = MusicFavoriteUiState(ids = setOf(track.id)).stageFavoriteRemoval(track)

        assertFalse(track.id in staged.ids)
        assertTrue(track.id in staged.deferredRemovalIds)
        assertEquals(false, staged.changes[track.id]?.second)
        assertTrue(staged.updating.isEmpty())

        val restored = staged.restoreFavoriteRemoval(track, previousChange = null)
        assertTrue(track.id in restored.ids)
        assertFalse(track.id in restored.deferredRemovalIds)
        assertEquals(true, restored.changes[track.id]?.second)
    }

    @Test
    fun authoritativeRefreshOnlyReconcilesSettledTracks() {
        val settled = track("qq-settled")
        val updating = track("qq-updating")
        val state = MusicFavoriteUiState(
            ids = setOf(updating.id),
            updating = setOf(updating.id),
        )

        val reconciled = state.withAuthoritativeFavorites(
            tracks = listOf(settled, updating),
            authoritativeIds = setOf(settled.id),
        )

        assertTrue(settled.id in reconciled.ids)
        assertTrue(updating.id in reconciled.ids)
        assertEquals(true, reconciled.changes[settled.id]?.second)
        assertFalse(updating.id in reconciled.changes)
    }

    private fun track(id: String) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = "歌",
        artists = "歌手",
        album = "专辑",
        durationMs = 180_000L,
        artworkStart = 0L,
        artworkEnd = 0L,
        artworkMark = "",
        previewUrl = "",
    )
}
