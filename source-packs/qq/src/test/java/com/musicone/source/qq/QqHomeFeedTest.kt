package com.musicone.source.qq

import com.musicone.source.qq.internal.*
import com.musicone.source.sdk.SourceCursorRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QqHomeFeedTest {
    @Test fun firstPageSignalsDeferredSongsWithoutPreparingThemOrFetchingAgain() = runBlocking {
        val shelves = (1..3).map { index -> QqMusicFeedCard.SongShelf(
            "207", "推荐$index", listOf(listOf(QqMusicFeedCard.Song(track("shelf-$index"), ""))),
        ) }
        val seed = QqMusicFeedCard.Song(track("seed"), "", lookupQuery = "待搜索")
        val group = QqMusicFeedCard.SongGroup("三首歌曲", listOf(track("one"), track("two"), track("three")))
        var requests = 0
        val prepared = mutableListOf<List<QqMusicFeedCard>>()
        val home = QqHomeFeed { QqMusicFeedPager(prepare = { prepared += it; it }, fetch = { _, _, _, _ ->
            requests++
            QqMusicFeedPage(shelves + seed + group, listOf("207", "315"), 3, emptyList(), 0)
        }) }

        val first = home.next(SourceCursorRequest(limit = 6), "account")
        assertEquals(shelves, first.items)
        assertEquals(listOf(shelves), prepared)
        val cursor = requireNotNull(first.nextCursor)
        assertTrue(home.hasBufferedItems(cursor))
        assertFalse(home.hasBufferedItems("$cursor:stale"))
        assertEquals(1, requests)

        val second = home.next(SourceCursorRequest(cursor, 9), "account")
        assertEquals(listOf(seed, group), second.items)
        assertEquals(listOf(shelves, listOf(seed, group)), prepared)
        assertEquals(1, requests)
        assertFalse(home.hasBufferedItems(cursor))
        assertFalse(home.hasBufferedItems(requireNotNull(second.nextCursor)))
        home.clear()
        assertFalse(home.hasBufferedItems(requireNotNull(second.nextCursor)))
    }

    @Test fun remoteNextPageAloneIsNotReportedAsBuffered() = runBlocking {
        var requests = 0
        val home = QqHomeFeed { QqMusicFeedPager { _, _, _, _ ->
            requests++
            QqMusicFeedPage(listOf(QqMusicFeedCard.Song(track("ready"), "")), listOf("315"), 1, emptyList(), 0)
        } }
        val page = home.next(SourceCursorRequest(limit = 6), "account")
        assertFalse(home.hasBufferedItems(requireNotNull(page.nextCursor)))
        assertEquals(1, requests)
    }

    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, id, "歌手", "", 0, 0, 0, id, "")
}
