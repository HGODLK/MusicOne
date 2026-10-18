package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistPagingTest {
    private fun track(index: Int) = MusicTrack("qq-$index", MusicSource.QQ, "歌曲$index", "歌手", "",
        180_000, 0, 0, "", "")
    private val shell = MusicPlaylist("qq-paged", MusicSource.QQ, "大歌单", "", "", 1_257, 0, 0, "", emptyList())
    private fun page(offset: Int, total: Int = shell.count): PlaylistTrackPage {
        val end = (offset + 100).coerceAtMost(total)
        return PlaylistTrackPage(shell.copy(count = total, tracks = (offset until end).map(::track)),
            nextPlaylistTrackCursor(offset, end - offset, total))
    }

    @Test fun browsingLoadsOnlyOnePageAndCompleteOperationReadsPastTheOldLimits() = runBlocking {
        val requests = mutableListOf<Int>()
        val loader = PlaylistPagingController { _, cursor ->
            val offset = cursor?.offset ?: 0
            requests += offset
            page(offset)
        }
        assertEquals(100, loader.first(shell).tracks.size)
        loader.first(shell)
        assertEquals(listOf(0), requests)
        assertEquals(200, loader.more(shell).tracks.size)
        val complete = loader.complete(shell)
        assertEquals(1_257, complete.tracks.size)
        assertEquals("qq-1256", complete.tracks.last().id)
        assertEquals((0..12).map { it * 100 }, requests)
        assertNull(loader.entry(shell).page?.next)
    }

    @Test fun concurrentFirstRequestsShareTheSamePage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val loader = PlaylistPagingController { _, _ ->
            requests++
            started.complete(Unit)
            release.await()
            page(0)
        }
        val first = async { loader.first(shell) }
        started.await()
        val second = async { loader.first(shell) }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(1, requests)
    }

    @Test fun aFailedPageKeepsSongsAndCursorAndRetryContinuesAtTheSameOffset() = runBlocking {
        var fail = true
        val offsets = mutableListOf<Int>()
        val loader = PlaylistPagingController { _, cursor ->
            val offset = cursor?.offset ?: 0
            offsets += offset
            if (offset == 100 && fail) { fail = false; error("网络失败") }
            page(offset)
        }
        val first = loader.first(shell)
        assertTrue(runCatching { loader.more(first) }.isFailure)
        val entry = loader.entry(shell)
        assertEquals(first, entry.page?.playlist)
        assertEquals(100, entry.page?.next?.offset)
        assertNotNull(entry.error)
        assertEquals(200, loader.more(first).tracks.size)
        assertEquals(listOf(0, 100, 100), offsets)
        assertNull(entry.error)
    }

    @Test fun aRepeatedServerPageDoesNotCreateDuplicatesOrAnEndlessLoad() = runBlocking {
        val loader = PlaylistPagingController { _, cursor ->
            page(0).copy(next = PlaylistTrackCursor((cursor?.offset ?: 0) + 100))
        }
        loader.first(shell)
        assertTrue(runCatching { loader.complete(shell) }.isFailure)
        assertEquals(100, loader.entry(shell).page?.playlist?.tracks?.size)
        assertNotNull(loader.entry(shell).error)
    }

    @Test fun anInvalidatedAccountCannotPublishItsPendingPage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = PlaylistPagingController { _, _ ->
            started.complete(Unit); release.await(); page(0)
        }
        val pending = async { runCatching { loader.first(shell) } }
        started.await()
        loader.clear()
        release.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertNull(loader.entry(shell).page)
    }

    @Test fun recentPlaySnapshotWithTheSameIdAlwaysUsesTheLatestSongs() = runBlocking {
        val loader = PlaylistPagingController { _, _ -> error("不应请求网络") }
        val recent = shell.copy(id = QQ_RECENT_SONGS_PLAYLIST_ID, tracks = listOf(track(1)))
        loader.first(recent)
        val updated = recent.copy(tracks = listOf(track(2)))
        assertEquals(updated, loader.first(updated))
    }

    @Test fun dailyMixSnapshotsRemainPlayableWithoutAPlaylistNetworkId() = runBlocking {
        val loader = PlaylistPagingController { _, _ -> error("每日推荐已经是完整快照") }
        val qq = shell.copy(id = "qq-daily-2026-10-17", tracks = listOf(track(1)), count = 1)
        val kugou = qq.copy(id = "kugou-daily", source = MusicSource.KUGOU)
        assertEquals(qq, loader.complete(qq))
        assertEquals(kugou, loader.complete(kugou))
    }

    @Test fun shortPagesUseTheActualServerCountAndKeepTheFirstCover() {
        assertEquals(37, nextPlaylistTrackCursor(0, 37, 500)?.offset)
        val first = page(0).let { it.copy(playlist = it.playlist.copy(artworkUrl = "first")) }
        val second = page(100).let { it.copy(playlist = it.playlist.copy(artworkUrl = "second")) }
        assertEquals("first", mergePlaylistTrackPage(first, second).playlist.artworkUrl)
        assertEquals(200, mergePlaylistTrackPage(first, second).playlist.tracks.size)
    }

    @Test fun emptyMissingPageFailsInsteadOfMarkingATruncatedListComplete() {
        assertTrue(runCatching { nextPlaylistTrackCursor(100, 0, 1_500) }.isFailure)
        assertNull(nextPlaylistTrackCursor(100, 0, 100))
        assertNull(nextPlaylistTrackCursor(100, 25, 0))
    }
}
