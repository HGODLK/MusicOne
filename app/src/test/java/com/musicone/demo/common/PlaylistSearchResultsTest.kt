package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaylistSearchResultsTest {
    private fun track(id: String, title: String = id) = MusicTrack(
        id, MusicSource.QQ, title, "歌手", "专辑", 1_000L, 0L, 0L, "", "",
    )
    private fun PlaylistSearchResults.ids() = rows.map { it.track.id }

    @Test fun rapidNarrowingAndClearingDoesNotLoseRowsOrAcceptOldExitCallbacks() {
        val tracks = listOf(track("1"), track("2"), track("3"))
        val results = PlaylistSearchResults(tracks)
        tracks.forEach { results.mount(it.id) }
        results.update(listOf(tracks[1]), true)
        assertEquals(listOf("1", "2", "3"), results.ids())
        assertEquals(setOf("2"), results.targetIds)
        results.update(listOf(tracks[2]), true)
        results.update(tracks, true)
        results.finishExit("1")
        results.finishExit("2")
        assertEquals(listOf("1", "2", "3"), results.ids())
        assertEquals(setOf("1", "2", "3"), results.targetIds)
    }

    @Test fun exitingRowsKeepTheirSlotsAndAreRemovedAfterExit() {
        val tracks = (1..5).map { track(it.toString()) }
        val results = PlaylistSearchResults(tracks)
        tracks.forEach { results.mount(it.id) }
        results.update(listOf(tracks[1], tracks[3], track("6")), true)
        assertEquals(listOf("1", "2", "3", "4", "6", "5"), results.ids())
        listOf("1", "3", "5").forEach(results::finishExit)
        assertEquals(listOf("2", "4", "6"), results.ids())
        assertFalse(results.rows.last().initiallyVisible)
    }

    @Test fun offscreenRowsAreNotRetainedAndCompletedEntranceDoesNotReplayAfterRecycling() {
        val results = PlaylistSearchResults(listOf(track("1"), track("2")))
        results.mount("1")
        results.update(listOf(track("3")), true)
        assertEquals(listOf("3", "1"), results.ids())
        results.unmount("1")
        results.finishEntrance("3")
        results.mount("3")
        results.unmount("3")
        results.update(listOf(track("3", "补全歌名")), true)
        assertEquals(listOf("3"), results.ids())
        assertTrue(results.rows.single().initiallyVisible)
        assertEquals("补全歌名", results.rows.single().track.title)
    }

    @Test fun disabledAnimationAndDuplicateResultsHaveOnlyTheLatestUniqueRows() {
        val results = PlaylistSearchResults(listOf(track("1"), track("2")))
        results.mount("1")
        results.update(listOf(track("3"), track("3")), false)
        assertEquals(listOf("3"), results.ids())
        assertTrue(results.rows.single().initiallyVisible)
    }

    @Test fun artistRemoteResultsBelongOnlyToTheirQueryAndQueueMatchesVisibleSongs() {
        val local = listOf(track("1", "晴天"), track("2", "红豆"))
        val remote = listOf(track("3", "远端歌曲"), local[0])
        assertEquals(listOf("1", "3"), artistSearchTracks(local, remote, "晴", "晴").map { it.id })
        assertEquals(listOf("2"), artistSearchTracks(local, remote, "晴", "红").map { it.id })
        assertEquals(local, artistSearchTracks(local, remote, "晴", " "))
    }
}
