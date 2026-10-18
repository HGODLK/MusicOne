package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistDeferredRemovalTest {
    private fun song(id: String) = MusicTrack(id, MusicSource.QQ, id, "", "", 0, 0, 0, "", "")
    private fun batch(id: String) = PlaylistRemovalBatch(
        MusicPlaylist(id, MusicSource.QQ, id, "", "", 0, 0, 0, "", emptyList()),
        PlatformSession(MusicSource.QQ, "account-$id", "device", null))

    @Test fun stagingKeepsRowsAndCanBeUndoneWithoutAnyWrite() = runBlocking {
        val batch = batch("one")
        val track = song("qq-1")
        batch.set(track, true)
        assertEquals(setOf(track.id), batch.ids)
        batch.set(track, false)
        var writes = 0
        commitPlaylistRemovals(batch) { writes++ }
        assertEquals(0, writes)
        assertTrue(batch.ids.isEmpty())
    }

    @Test fun sameSongInDifferentPlaylistsHasIndependentUndoAndCommit() = runBlocking {
        val a = batch("a"); val b = batch("b"); val track = song("qq-1")
        a.set(track, true); b.set(track, true); a.set(track, false)
        val writes = mutableListOf<String>()
        commitPlaylistRemovals(a) { writes += "a" }
        assertEquals(setOf(track.id), b.ids)
        commitPlaylistRemovals(b) { writes += "b" }
        assertEquals(listOf("b"), writes)
    }

    @Test fun partialFailureOnlyConfirmsSuccessfulSongsAndLeavesTheOthersInThePlaylist() = runBlocking {
        val batch = batch("one")
        val tracks = listOf(song("qq-1"), song("qq-2"), song("qq-3"))
        tracks.forEach { batch.set(it, true) }
        val stored = tracks.toMutableList()
        val result = commitPlaylistRemovals(batch) {
            if (it.id == "qq-2") error("离线")
            stored.remove(it)
        }
        assertEquals(setOf("qq-1"), result.completed)
        assertEquals(listOf("qq-2", "qq-3"), stored.map { it.id })
        assertNotNull(result.error)
        assertTrue(batch.ids.isEmpty())
    }

    @Test fun submittingCannotBeUndoneOrDoubleSubmittedAndCancellationReleasesWritingState() = runBlocking {
        val batch = batch("one"); val track = song("qq-1")
        batch.set(track, true)
        try {
            commitPlaylistRemovals(batch) {
                batch.set(track, false)
                assertEquals(setOf(track.id), batch.ids)
                assertTrue(commitPlaylistRemovals(batch) { fail("不能重复写入") }.completed.isEmpty())
                throw CancellationException()
            }
            fail("应保留取消信号")
        } catch (_: CancellationException) { assertTrue(batch.writing.isEmpty()) }
    }

    @Test fun batchedPlatformWriteIsSubmittedOnceAndConfirmedOnlyAfterSuccess() = runBlocking {
        val batch = batch("one"); val tracks = listOf(song("qq-1"), song("qq-2"))
        tracks.forEach { batch.set(it, true) }
        var calls = 0
        val result = commitPlaylistRemovals(batch, writeAll = { songs ->
            calls++; assertEquals(tracks, songs)
            assertEquals(setOf("qq-1", "qq-2"), batch.ids)
        }) { fail("平台批量接口不应降成逐首调用") }
        assertEquals(1, calls)
        assertEquals(setOf("qq-1", "qq-2"), result.completed)
        tracks.forEach { batch.set(it, true) }
        val failed = commitPlaylistRemovals(batch, writeAll = { error("离线") }) { fail() }
        assertTrue(failed.completed.isEmpty())
        assertNotNull(failed.error)
        assertTrue(batch.ids.isEmpty())
    }
}
