package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistMultiSelectionTest {
    private fun track(index: Int) = MusicTrack("qq-$index", MusicSource.QQ, "歌曲$index", "歌手", "",
        180_000, 0, 0, "", "")
    private val shell = MusicPlaylist("qq-selection", MusicSource.QQ, "歌单", "", "", 257, 0, 0, "", emptyList())

    @Test fun selectAllLoadsEveryPageAndKeepsTheDisplayedOrder() = runBlocking {
        val loader = PlaylistPagingController { _, cursor ->
            val offset = cursor?.offset ?: 0
            val end = (offset + 100).coerceAtMost(shell.count)
            PlaylistTrackPage(shell.copy(tracks = (offset until end).map(::track)),
                nextPlaylistTrackCursor(offset, end - offset, shell.count))
        }
        val selection = PlaylistMultiSelection()
        selection.update(loader.first(shell).tracks)
        selection.begin("qq-0")
        selection.selectAll { sortedPlaylistTracks(loader.complete(shell).tracks, PlaylistSort.ADDED_ASC) }
        assertEquals(257, selection.ids.size)
        assertEquals((0 until 257).reversed().map { "qq-$it" }, selection.tracks.map { it.id })
        selection.toggle("qq-128")
        assertEquals(256, selection.tracks.count { it.id in selection.ids })
        assertFalse(selection.loading)
    }

    @Test fun failedPageKeepsPreviousSelectionAndCanBeRetried() = runBlocking {
        val selection = PlaylistMultiSelection()
        selection.update(listOf(track(0)))
        selection.begin("qq-0")
        selection.selectAll { error("分页失败") }
        assertEquals(setOf("qq-0"), selection.ids)
        assertNotNull(selection.error)
        assertFalse(selection.loading)
        selection.selectAll { listOf(track(0), track(1)) }
        assertEquals(setOf("qq-0", "qq-1"), selection.ids)
        assertNull(selection.error)
    }

    @Test fun closingSelectionDiscardsAnInFlightSelectAll() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<List<MusicTrack>>()
        val selection = PlaylistMultiSelection()
        selection.begin("qq-0")
        val pending = async { selection.selectAll { started.complete(Unit); completed.await() } }
        started.await()
        assertTrue(selection.loading)
        selection.close()
        completed.complete(listOf(track(0), track(1)))
        pending.await()
        assertFalse(selection.active)
        assertTrue(selection.ids.isEmpty())
        assertFalse(selection.loading)
    }
}
