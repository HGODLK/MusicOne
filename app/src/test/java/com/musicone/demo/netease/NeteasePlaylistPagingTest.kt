package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteasePlaylistPagingTest {
    @Test fun theFullIdDirectoryDoesNotCauseAllSongDetailsToLoadOnOpen() {
        val total = 1_205
        val requests = mutableListOf<List<String>>()
        var detailRequests = 0
        val loader = NeteasePlaylistPaging({ url, _ ->
            detailRequests++
            assertTrue(url.contains("n=100&"))
            JSONObject().put("code", 200).put("playlist", JSONObject().put("id", 1).put("name", "完整歌单")
                .put("trackCount", total).put("trackIds", JSONArray().apply {
                    (1..total).forEach { put(JSONObject().put("id", it)) }
                }))
        }, { ids, _, _ ->
            requests += ids
            ids.map { MusicTrack("netease-$it", MusicSource.NETEASE, it, "", "", 0, 0, 0, "", "") }
        })
        val shell = MusicPlaylist("netease-1", MusicSource.NETEASE, "", "", "", total, 0, 0, "", emptyList(), isOwned = true)
        val first = loader.page(shell, "", false, null)
        assertEquals(100, first.playlist.tracks.size)
        assertEquals(1, requests.size)
        assertTrue(first.playlist.isOwned)
        var current = first
        while (current.next != null) {
            current = mergePlaylistTrackPage(current, loader.page(current.playlist, "", false, current.next))
        }
        assertEquals(1, detailRequests)
        assertEquals(total, current.playlist.tracks.size)
        assertEquals("netease-1205", current.playlist.tracks.last().id)
        assertTrue(requests.all { it.size <= 100 })
    }
}
