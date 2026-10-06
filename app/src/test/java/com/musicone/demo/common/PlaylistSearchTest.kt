package com.musicone.demo

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSearchTest {
    private fun track(id: String, title: String, artists: String) = MusicTrack(
        id, MusicSource.QQ, title, artists, "专辑", 1_000L, 0L, 0L, "", "",
    )

    @Test
    fun queryMatchesSongTitleOrCreator() {
        val tracks = listOf(
            track("1", "晴天", "周杰伦"),
            track("2", "红豆", "王菲"),
            track("3", "夜曲", "周杰伦"),
        )

        assertEquals(listOf("1", "3"), tracks.matchingPlaylistQuery("周杰伦").map { it.id })
        assertEquals(listOf("2"), tracks.matchingPlaylistQuery("红豆").map { it.id })
        assertEquals(tracks, tracks.matchingPlaylistQuery("  "))
    }

    @Test
    fun lazyListIndexAccountsForPlaylistHeader() {
        assertEquals(8, playlistTrackItemIndex(wide = true, trackIndex = 7))
        assertEquals(10, playlistTrackItemIndex(wide = false, trackIndex = 7))
        assertEquals(12, playlistTrackItemIndex(wide = false, trackIndex = 7, extraHeaderItems = 2))
    }

    @Test
    fun playlistLocateOffsetCentersTrackInsideViewport() {
        assertEquals(310, playlistCenteredScrollOffset(viewportStart = 0, viewportEnd = 700, itemSize = 80))
        assertEquals(322, playlistCenteredScrollOffset(viewportStart = 24, viewportEnd = 700, itemSize = 80))
    }

    @Test
    fun backButtonTravelsFullyOffscreenWithPageMotion() {
        assertEquals(-76f, playlistBackButtonOffset(0f, 76f))
        assertEquals(-38f, playlistBackButtonOffset(.5f, 76f))
        assertEquals(0f, playlistBackButtonOffset(1f, 76f))
    }

    @Test
    fun searchResultsReserveTheKeyboardAndFloatingFieldArea() {
        assertEquals(408.dp, playlistSearchResultsBottomPadding(96.dp, 324.dp, true))
        assertEquals(120.dp, playlistSearchResultsBottomPadding(96.dp, 324.dp, false))
    }

    @Test
    fun searchViewportMovesOnlyAfterARealQueryAndKeepsPartOfTheCover() {
        assertEquals(false, playlistSearchHasQuery("   "))
        assertEquals(true, playlistSearchHasQuery("老"))
        assertEquals(720, playlistSearchCoverScrollOffset(870, 150))
        assertEquals(0, playlistSearchCoverScrollOffset(120, 150))
    }
}
