package com.musicone.source.qq.internal

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QqFeedAppendPreparationTest {
    @Test fun differentSongShelvesStaySeparateAndKeepExistingFlowObjects() {
        val firstShelf = shelf("first")
        val oldSong = song("old")
        val old = listOf(firstShelf, oldSong)
        val newShelf = shelf("second")
        val newSong = song("new")

        val result = appendQqMusicFeedDisplayCards(old, listOf(newSong, newShelf, oldSong), false)

        assertEquals(listOf(firstShelf, newShelf, oldSong, newSong), result)
        assertSame(firstShelf, result[0])
        assertSame(newShelf, result[1])
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
        assertEquals(3, displayed.count { it is QqMusicFeedCard.SongShelf })
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

    private fun shelf(id: String) = QqMusicFeedCard.SongShelf(
        id, "歌曲推荐", listOf(listOf(song("shelf-$id"))),
    )

    private fun song(id: String) = QqMusicFeedCard.Song(MusicTrack(
        id = id, source = MusicSource.QQ, title = id, artists = "歌手", album = "",
        durationMs = 0, artworkStart = 0, artworkEnd = 0, artworkMark = id, previewUrl = "",
    ), "推荐语")
}
