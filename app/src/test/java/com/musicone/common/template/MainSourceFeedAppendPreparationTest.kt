package com.musicone

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MainSourceFeedAppendPreparationTest {
    @Test fun initialPlaceholderStopsAfterSuccessOrFailureAndDoesNotReplaceCachedCards() {
        assertTrue(MainSourceHomeState().toContent().initialLoading)
        assertTrue(MainSourceHomeState(loading = true).toContent().initialLoading)
        assertFalse(MainSourceHomeState(cards = listOf(song("cached"))).toContent().initialLoading)
        assertFalse(MainSourceHomeState(settled = true).toContent().initialLoading)
        assertFalse(MainSourceHomeState(message = "加载失败").toContent().initialLoading)
    }

    @Test fun loadingOnlyChangesDoNotPublishNewHomeContent() = runBlocking {
        val ready = MainSourceHomeState(cards = listOf(song("old")), settled = true)
        val loading = ready.copy(loading = true)
        val appended = ready.copy(cards = ready.cards + song("new"))
        val failed = appended.copy(automaticLoading = false, message = "加载失败")
        val refreshing = appended.copy(refreshing = true)
        val refreshed = ready.copy(generation = 1)

        val content = flowOf(ready, loading, ready, appended, failed, refreshing, refreshed)
            .map(MainSourceHomeState::toContent).distinctUntilChanged().toList()

        assertEquals(5, content.size)
        assertSame(ready.cards, content[0].cards)
        assertSame(appended.cards, content[1].cards)
        assertEquals("加载失败", content[2].message)
        assertTrue(content[3].refreshing)
        assertEquals(1L, content[4].generation)
    }

    @Test fun warmupCoversBothPhoneColumnsAndTabletOrientations() {
        assertEquals(listOf(481, 482), mainSourceFeedCardPixelWidths(1125, 60, 42, 2))
        assertEquals(listOf(768), mainSourceFeedCardPixelWidths(2560, 100, 28, 3))
        assertEquals(listOf(472), mainSourceFeedCardPixelWidths(1600, 64, 28, 3))
        assertEquals(listOf(1), mainSourceFeedCardPixelWidths(20, 20, 14, 2))
    }

    private fun shelf(id: String) = MainSourceHomeCard.SongShelf(
        id, "歌曲推荐", listOf(listOf(song("shelf-$id"))),
    )

    private fun song(id: String) = MainSourceHomeCard.Song(MusicTrack(
        id = id, source = MusicSource.QQ, title = id, artists = "歌手", album = "",
        durationMs = 0, artworkStart = 0, artworkEnd = 0, artworkMark = id, previewUrl = "",
    ), "推荐语")
}
