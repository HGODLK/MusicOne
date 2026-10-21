package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class QqPlaylistPagingTest {
    private val shell = MusicPlaylist("qq-12345", MusicSource.QQ, "歌单", "", "", 1_200, 0, 0, "", emptyList())

    @Test fun publicPlaylistRequestHasAnExplicitOffsetAndBoundedPageSize() {
        val request = qqPlaylistPageRequest(shell, 1_100, "").getJSONObject("playlist")
        assertEquals("CgiGetDiss", request.getString("method"))
        val params = request.getJSONObject("param")
        assertEquals(12345L, params.getLong("disstid"))
        assertEquals(0, params.getInt("dirid"))
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

    @Test fun favoritesReadAll1462SongsAndKeepBothMidAndNumericIdentities() {
        val offsets = mutableListOf<Int>()
        val total = 1_462
        val client = QqLibraryClient(QqPlaylistPaging { body, _ ->
            val params = body.getJSONObject("playlist").getJSONObject("param")
            assertEquals(0L, params.getLong("disstid"))
            assertEquals(201, params.getInt("dirid"))
            assertEquals(100, params.getInt("song_num"))
            val offset = params.getInt("song_begin")
            offsets += offset
            favoritesResponse(offset, total)
        })
        val ids = client.likedTrackIds("uin=123; qqmusic_key=test", "123")
        assertEquals((0..14).map { it * 100 }, offsets)
        assertEquals(total * 2, ids.size)
        assertTrue("qq-${mid(1461)}" in ids)
        assertTrue("qq-1462" in ids)
    }

    @Test fun favoriteSelectionCompletesThroughTheSharedPagingController() = runBlocking {
        val client = QqLibraryClient(QqPlaylistPaging { body, _ ->
            favoritesResponse(body.getJSONObject("playlist").getJSONObject("param").getInt("song_begin"), 1_462)
        })
        val favorites = shell.copy(id = QQ_FAVORITES_PLAYLIST_ID)
        val loader = PlaylistPagingController { _, cursor ->
            client.favoritePlaylistPage("uin=123; qqmusic_key=test", "123", false,
                cursor?.offset ?: 0, resolveAccess = false)
        }
        assertEquals(100, loader.first(favorites).tracks.size)
        val full = loader.complete(favorites)
        assertEquals(QQ_FAVORITES_PLAYLIST_ID, full.id)
        assertEquals("我喜欢的音乐", full.title)
        assertEquals("喜", full.artworkMark)
        assertEquals(1_462, full.count)
        assertEquals(1_462, full.tracks.size)
        assertEquals("1462", full.tracks.last().catalogId)
        assertNull(loader.entry(favorites).page?.next)
    }

    @Test fun repeatedFavoritePageKeepsTheLoadedSongsAndRetryOffset() = runBlocking {
        var repeat = true
        val client = QqLibraryClient(QqPlaylistPaging { body, _ ->
            val offset = body.getJSONObject("playlist").getJSONObject("param").getInt("song_begin")
            favoritesResponse(if (repeat) 0 else offset, 1_462)
        })
        val favorites = shell.copy(id = QQ_FAVORITES_PLAYLIST_ID)
        val loader = PlaylistPagingController { _, cursor ->
            client.favoritePlaylistPage("uin=123; qqmusic_key=test", "123", false,
                cursor?.offset ?: 0, resolveAccess = false)
        }
        loader.first(favorites)
        assertTrue(runCatching { loader.complete(favorites) }.isFailure)
        val entry = loader.entry(favorites)
        assertEquals(100, entry.page?.playlist?.tracks?.size)
        assertEquals(100, entry.page?.next?.offset)
        assertNotNull(entry.error)
        repeat = false
        assertEquals(1_462, loader.complete(favorites).tracks.size)
        assertNull(entry.error)
    }

    private fun mid(index: Int) = "0039MnYb" + index.toString().padStart(6, '0')

    private fun favoritesResponse(offset: Int, total: Int): JSONObject {
        val end = (offset + 100).coerceAtMost(total)
        val songs = JSONArray()
        (offset until end).forEach {
            songs.put(JSONObject().put("mid", mid(it)).put("id", it + 1).put("name", "歌曲$it"))
        }
        return JSONObject().put("code", 0).put("playlist", JSONObject().put("code", 0).put("data",
            JSONObject().put("dirinfo", JSONObject().put("title", "我喜欢"))
                .put("songlist", songs).put("total_song_num", total).put("hasmore", if (end < total) 1 else 0)))
    }
}
