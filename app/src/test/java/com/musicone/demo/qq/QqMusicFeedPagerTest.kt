package com.musicone.demo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QqMusicFeedPagerTest {
    @Test
    fun pagesUseCurrentShelfCountAndKeepCardTypesBalanced() = runBlocking {
        val requests = mutableListOf<Triple<Int, Int, List<String>>>()
        val pager = QqMusicFeedPager { page, count, shelves, _ ->
            requests += Triple(page, count, shelves)
            response(
                listOf(
                    QqMusicFeedCard.Song(track("song-$page"), "推荐$page"),
                    QqMusicFeedCard.Playlist(playlist("playlist-$page")),
                ),
                count = 2,
                shelfIds = listOf("207", "315"),
            )
        }

        val batch = pager.next(emptyList(), size = 4, replace = false)

        assertEquals(
            setOf("song:song-1", "playlist:playlist-1", "song:song-2", "playlist:playlist-2"),
            batch.visible.map { it.key }.toSet(),
        )
        assertTrue(batch.visible.zipWithNext().all { (left, right) ->
            (left is QqMusicFeedCard.Song) != (right is QqMusicFeedCard.Song)
        })
        assertEquals(listOf(Triple(1, 0, emptyList()), Triple(2, 2, listOf("207", "315"))), requests)
    }

    @Test
    fun loadedPoolDistributesSongsAndPlaylistsWithoutExtraRequests() = runBlocking {
        val cards = (1..6).map { QqMusicFeedCard.Song(track("song-$it"), "推荐$it") } +
            (1..3).map { QqMusicFeedCard.Playlist(playlist("playlist-$it")) }
        var requestCount = 0
        val pager = QqMusicFeedPager { _, _, _, _ ->
            requestCount++
            response(cards)
        }

        val batch = pager.next(emptyList(), size = 6, replace = false)

        assertEquals(3, batch.visible.count { it is QqMusicFeedCard.Playlist })
        assertEquals(3, batch.visible.count { it is QqMusicFeedCard.Song })
        assertEquals(3, batch.remaining.count { it is QqMusicFeedCard.Song })
        assertEquals(0, batch.remaining.count { it is QqMusicFeedCard.Playlist })
        assertEquals(1, requestCount)
    }

    @Test
    fun threeRowShelfRemainsOneCardAndDuplicatePagesAreSkipped() = runBlocking {
        val shelf = QqMusicFeedCard.SongShelf(
            shelfId = "207",
            title = "宝藏好歌",
            pages = listOf((1..3).map { QqMusicFeedCard.Song(track("row-$it"), "文案$it") }),
        )
        val pager = QqMusicFeedPager { page, _, _, _ ->
            response(if (page == 1) listOf(shelf) else listOf(shelf, QqMusicFeedCard.Song(track("fresh"), "")))
        }

        val first = pager.next(emptyList(), size = 1, replace = false)
        assertEquals(listOf(shelf.key), first.visible.map { it.key })
        val second = pager.next(first.visible, size = 1, replace = false)
        assertEquals(listOf("song:fresh"), second.visible.map { it.key })
    }

    @Test
    fun requestFailureDoesNotAdvanceCursorAndRetryRecovers() = runBlocking {
        val requests = mutableListOf<Int>()
        var failOnce = true
        val pager = QqMusicFeedPager { page, _, _, _ ->
            requests += page
            if (failOnce) {
                failOnce = false
                error("请求失败")
            }
            response(listOf(QqMusicFeedCard.Song(track("song"), "")))
        }

        try {
            pager.next(emptyList(), size = 1, replace = false)
            fail("第一次请求应该失败")
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf("song:song"), pager.next(emptyList(), size = 1, replace = false).visible.map { it.key })
        assertEquals(listOf(1, 1), requests)
    }

    @Test
    fun replaceStartsAFreshOfficialCursor() = runBlocking {
        val pages = mutableListOf<Int>()
        val pager = QqMusicFeedPager { page, _, _, _ ->
            pages += page
            response(listOf(QqMusicFeedCard.Song(track("song-$page"), "")))
        }

        pager.next(emptyList(), size = 1, replace = false)
        pager.next(emptyList(), size = 1, replace = true)

        assertEquals(listOf(1, 1), pages)
    }

    @Test
    fun refreshAcceptsTheSameFirstPageAgain() = runBlocking {
        val page = QqMusicFeedCard.Song(track("same"), "")
        val pager = QqMusicFeedPager { _, _, _, _ -> response(listOf(page)) }

        val first = pager.next(emptyList(), size = 1, replace = false)
        val refreshed = pager.next(first.visible, size = 1, replace = true)

        assertEquals(listOf(page.key), refreshed.visible.map(QqMusicFeedCard::key))
    }

    @Test
    fun emptyResponseStopsWithoutPretendingToHaveContent() = runBlocking {
        val pager = QqMusicFeedPager { _, _, _, _ -> response(emptyList(), count = 0) }
        try {
            pager.next(emptyList(), size = 1, replace = false)
            fail("空响应不能伪造成成功")
        } catch (error: PlatformApiException) {
            assertTrue(error.message.orEmpty().contains("没有更多"))
        }
    }

    private fun response(
        cards: List<QqMusicFeedCard>,
        count: Int = 1,
        shelfIds: List<String> = listOf("315"),
    ) = QqMusicFeedPage(cards, shelfIds, count, emptyList(), 0)

    private fun track(id: String) = MusicTrack(
        id = id,
        source = MusicSource.QQ,
        title = id,
        artists = "歌手",
        album = "",
        durationMs = 0,
        artworkStart = 0,
        artworkEnd = 0,
        artworkMark = id,
        previewUrl = "",
    )

    private fun playlist(id: String) = MusicPlaylist(
        id = id,
        source = MusicSource.QQ,
        title = id,
        subtitle = "",
        description = "",
        count = 0,
        artworkStart = 0,
        artworkEnd = 0,
        artworkMark = id,
        tracks = emptyList(),
    )
}
