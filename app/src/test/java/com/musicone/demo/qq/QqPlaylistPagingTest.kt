package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqPlaylistPagingTest {
    private val shell = MusicPlaylist("qq-12345", MusicSource.QQ, "歌单", "", "", 1_200, 0, 0, "", emptyList())

    @Test fun publicPlaylistRequestHasAnExplicitOffsetAndBoundedPageSize() {
        val request = qqPlaylistPageRequest(shell, 1_100, "").getJSONObject("playlist")
        assertEquals("CgiGetDiss", request.getString("method"))
        val params = request.getJSONObject("param")
        assertEquals(12345L, params.getLong("disstid"))
        assertEquals(1_100, params.getInt("song_begin"))
        assertEquals(100, params.getInt("song_num"))
    }

    @Test fun publicPlaylistUsesTheReportedTotalAndHasMoreFlag() {
        val data = JSONObject().put("dirinfo", JSONObject().put("title", "完整歌单"))
            .put("songlist", JSONArray().put(JSONObject().put("songmid", "0039MnYb0qxYhV")
                .put("songname", "歌曲").put("songid", 1)))
            .put("total_song_num", 1_501).put("hasmore", 1)
        val page = data.parseQqPlaylistTrackPage(shell, false, 1_100)
        assertEquals(1_501, page.playlist.count)
        assertEquals("完整歌单", page.playlist.title)
        assertEquals(1_101, page.next?.offset)
        data.put("hasmore", 0)
        assertNull(data.parseQqPlaylistTrackPage(shell, false, 1_500).next)
    }

    @Test fun favoritesReadBeyondFiveHundredAndKeepBothMidAndNumericIdentities() {
        val offsets = mutableListOf<Int>()
        val total = 1_205
        val client = QqLibraryClient { url, _, _ ->
            val query = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val offset = query.getValue("sin").toInt()
            val end = query.getValue("ein").toInt()
            offsets += offset
            assertEquals(offset + 99, end)
            val songs = JSONArray()
            (offset..end.coerceAtMost(total - 1)).forEach {
                songs.put(JSONObject().put("data", JSONObject().put("songmid", "mid$it").put("songid", it + 1)))
            }
            JSONObject().put("code", 0).put("data", JSONObject().put("totalsong", total).put("songlist", songs)).toString()
        }
        val ids = client.likedTrackIds("uin=123; qqmusic_key=test", "123")
        assertEquals((0..12).map { it * 100 }, offsets)
        assertEquals(total * 2, ids.size)
        assertTrue("qq-mid1204" in ids)
        assertTrue("qq-1205" in ids)
    }
}
