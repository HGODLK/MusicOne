package com.musicone.demo

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QqFeedAppendPreparationTest {
    @Test fun initialPlaceholderStopsAfterSuccessOrFailureAndDoesNotReplaceCachedCards() {
        assertTrue(QqMusicFeedState().toContent().initialLoading)
        assertTrue(QqMusicFeedState(loading = true).toContent().initialLoading)
        assertFalse(QqMusicFeedState(cards = listOf(song("cached"))).toContent().initialLoading)
        assertFalse(QqMusicFeedState(settled = true).toContent().initialLoading)
        assertFalse(QqMusicFeedState(message = "加载失败").toContent().initialLoading)
    }

    @Test fun laterSongShelvesStayAboveMusicFlowAndKeepExistingObjects() {
        val firstShelf = shelf("first")
        val oldSong = song("old")
        val old = listOf(firstShelf, oldSong)
        val newShelf = shelf("second")
        val newSong = song("new")

        val result = appendQqMusicFeedDisplayCards(old, listOf(newSong, newShelf, oldSong), false)

        assertEquals(listOf(firstShelf, newShelf, oldSong, newSong), result)
        assertSame(firstShelf, result[0])
        assertSame(oldSong, result[2])
    }

    @Test fun consecutivePagesKeepRecommendationsTogetherAndFlowInAppendOrder() {
        val shelves = (1..3).map { shelf("shelf-$it") }
        val songs = (1..3).map { song("song-$it") }
        var displayed = emptyList<QqMusicFeedCard>()
        repeat(3) { index ->
            displayed = appendQqMusicFeedDisplayCards(
                displayed, listOf(songs[index], shelves[index]), replace = index == 0,
            )
        }

        assertEquals(shelves + songs, displayed)
        (shelves + songs).forEachIndexed { index, card -> assertSame(card, displayed[index]) }
    }

    @Test fun refreshStartsWithTheNewRecommendationSectionOnly() {
        val old = listOf(song("old"))
        val freshShelf = shelf("fresh")
        val freshSong = song("fresh")

        val result = appendQqMusicFeedDisplayCards(old, listOf(freshSong, freshShelf), true)

        assertEquals(listOf(freshShelf, freshSong), result)
        assertFalse(result.any { it.key == old.single().key })
    }

    @Test fun loadingOnlyChangesDoNotPublishNewHomeContent() = runBlocking {
        val ready = QqMusicFeedState(cards = listOf(song("old")), settled = true)
        val loading = ready.copy(loading = true)
        val appended = ready.copy(cards = ready.cards + song("new"))
        val failed = appended.copy(automaticLoading = false, message = "加载失败")
        val refreshing = appended.copy(refreshing = true)
        val refreshed = ready.copy(generation = 1)

        val content = flowOf(ready, loading, ready, appended, failed, refreshing, refreshed)
            .map(QqMusicFeedState::toContent).distinctUntilChanged().toList()

        assertEquals(5, content.size)
        assertSame(ready.cards, content[0].cards)
        assertSame(appended.cards, content[1].cards)
        assertEquals("加载失败", content[2].message)
        assertTrue(content[3].refreshing)
        assertEquals(1L, content[4].generation)
    }

    @Test fun warmupCoversBothPhoneColumnsAndTabletOrientations() {
        assertEquals(listOf(481, 482), qqFeedCardPixelWidths(1125, 60, 42, 2))
        assertEquals(listOf(768), qqFeedCardPixelWidths(2560, 100, 28, 3))
        assertEquals(listOf(472), qqFeedCardPixelWidths(1600, 64, 28, 3))
        assertEquals(listOf(1), qqFeedCardPixelWidths(20, 20, 14, 2))
    }

    private fun shelf(id: String) = QqMusicFeedCard.SongShelf(
        id, "歌曲推荐", listOf(listOf(song("shelf-$id"))),
    )

    private fun song(id: String) = QqMusicFeedCard.Song(MusicTrack(
        id = id, source = MusicSource.QQ, title = id, artists = "歌手", album = "",
        durationMs = 0, artworkStart = 0, artworkEnd = 0, artworkMark = id, previewUrl = "",
    ), "推荐语")
}
