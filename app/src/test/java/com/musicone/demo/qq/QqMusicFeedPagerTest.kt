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
        assertEquals(1, requests.size)
        val next = pager.next(batch.visible, size = 4, replace = false)

        assertEquals(
            setOf("song:song-1", "playlist:playlist-1", "song:song-2", "playlist:playlist-2"),
            (batch.visible + next.visible).map { it.key }.toSet(),
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
    fun changingShelfPagesDoNotTriggerExtraProbesAndKeepRawCursor() = runBlocking {
        val requests = mutableListOf<Triple<Int, Int, List<String>>>()
        val pager = QqMusicFeedPager { page, count, shelves, _ ->
            requests += Triple(page, count, shelves)
            response(listOf(QqMusicFeedCard.SongShelf(
                "207", "官方推荐", listOf(listOf(QqMusicFeedCard.Song(track("row-$page"), ""))),
            )), count = 2, shelfIds = listOf("207", "207"))
        }
        val first = pager.next(emptyList(), size = 3, replace = false)
        val shelf = first.visible.single() as QqMusicFeedCard.SongShelf
        assertEquals(3, shelf.pages.size)
        assertEquals(listOf(1, 2, 3), requests.map { it.first })
        assertEquals(listOf(0, 2, 4), requests.map { it.second })
        val second = pager.next(first.visible, size = 1, replace = false)
        val merged = appendQqMusicFeedDisplayCards(first.visible, second.visible, false)
            .single() as QqMusicFeedCard.SongShelf
        assertEquals(4, merged.pages.size)
        assertEquals(Triple(4, 6, listOf("207")), requests.last())
        assertEquals(shelf.key, merged.key)
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

    @Test
    fun threeOfficialRowsPublishWithoutPreparingDeferredFlowOrFetchingMore() = runBlocking {
        val shelves = (1..3).map { index -> QqMusicFeedCard.SongShelf(
            "207", "因为你听过歌曲$index", listOf(listOf(QqMusicFeedCard.Song(track("shelf-$index"), ""))),
        ) }
        val seed = QqMusicFeedCard.Song(track("seed"), "", lookupQuery = "待搜索")
        val group = QqMusicFeedCard.SongGroup("待核对专辑", listOf(track("one"), track("two"), track("three")))
        val prepared = mutableListOf<List<QqMusicFeedCard>>()
        var requests = 0
        val pager = QqMusicFeedPager(prepare = { prepared += it; it }, fetch = { _, _, _, _ ->
            requests++
            response(shelves + seed + group, count = 3)
        })
        val first = pager.next(emptyList(), size = 6, replace = true)
        assertEquals(shelves, first.visible)
        assertEquals(listOf(seed, group), first.remaining)
        assertEquals(listOf(shelves), prepared)
        assertEquals(1, requests)
        val second = pager.next(first.visible, size = 9, replace = false)
        assertEquals(listOf(seed, group), second.visible)
        assertEquals(1, requests)
        assertEquals(listOf(seed, group), prepared[1])
    }

    @Test
    fun preparationFailureRetriesTheSavedResponseWithoutAdvancingCursorAgain() = runBlocking {
        var requests = 0
        var failOnce = true
        val card = QqMusicFeedCard.Song(track("saved"), "")
        val pager = QqMusicFeedPager(prepare = {
            if (failOnce) { failOnce = false; error("准备失败") }
            it
        }, fetch = { _, _, _, _ -> requests++; response(listOf(card)) })
        try {
            pager.next(emptyList(), size = 6, replace = false)
            fail("应传播准备失败")
        } catch (_: IllegalStateException) { }
        assertEquals(listOf(card), pager.next(emptyList(), size = 6, replace = false).visible)
        assertEquals(1, requests)
    }

    @Test
    fun invalidPreparedCardsDoNotBlockTheNextOfficialPage() = runBlocking {
        val invalid = QqMusicFeedCard.SongGroup("未知专辑", listOf(track("bad")))
        val good = QqMusicFeedCard.Song(track("good"), "")
        val pages = mutableListOf<Int>()
        val pager = QqMusicFeedPager(prepare = { it.filterNot { card -> card is QqMusicFeedCard.SongGroup } },
            fetch = { page, _, _, _ -> pages += page; response(if (page == 1) listOf(invalid) else listOf(good)) })
        try {
            pager.next(emptyList(), 1, false)
            fail("未确认的卡片不能发布")
        } catch (_: PlatformApiException) { }
        assertEquals(listOf(good), pager.next(emptyList(), 1, false).visible)
        assertEquals(listOf(1, 2), pages)
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
