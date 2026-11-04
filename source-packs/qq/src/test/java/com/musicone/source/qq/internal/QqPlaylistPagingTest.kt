package com.musicone.source.qq.internal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class QqPlaylistPagingTest {
    private val shell = MusicPlaylist("qq-12345", MusicSource.QQ, "歌单", "", "", 1_200, 0, 0, "", emptyList())

    @Test fun titleOnlyRequestUsesWebSchemaAndOneSongWithoutNeedingSongData() {
        val cookie = "uin=123456; qqmusic_key=fixture-key; loginType=2"
        val paging = QqPlaylistPaging { body, actualCookie ->
            assertEquals(cookie, actualCookie)
            val comm = body.getJSONObject("comm")
            assertEquals(19, comm.getInt("ct"))
            assertEquals(0, comm.getInt("cv"))
            assertFalse(comm.has("v"))
            assertEquals(qqPlaybackComm(cookie).get("authst"), comm.get("authst"))
            val param = body.getJSONObject("playlist").getJSONObject("param")
            assertEquals(12345L, param.getLong("disstid"))
            assertEquals(0, param.getInt("song_begin"))
            assertEquals(1, param.getInt("song_num"))
            titleResponse(JSONObject().put("title", "𝓐歌单🎵"))
        }
        assertEquals("𝓐歌单🎵", paging.title(shell, cookie))
    }

    @Test fun titleOnlyResponseFallsBackToDissnameAndRejectsEveryErrorLayer() {
        assertEquals("备用标题", QqPlaylistPaging { _, _ ->
            titleResponse(JSONObject().put("title", "").put("dissname", "备用标题"))
        }.title(shell, ""))
        val failures = listOf(
            JSONObject(),
            titleResponse(JSONObject()),
            titleResponse(JSONObject().put("title", "标题")).put("code", 1),
            titleResponse(JSONObject().put("title", "标题")).apply {
                getJSONObject("playlist").put("code", 1)
            },
            titleResponse(JSONObject().put("title", "标题")).apply {
                getJSONObject("playlist").getJSONObject("data").put("code", 1)
            },
            titleResponse(JSONObject().put("title", "标题")).apply {
                getJSONObject("playlist").getJSONObject("data").put("subcode", 1)
            },
        )
        failures.forEach { response ->
            assertNull(QqPlaylistPaging { _, _ -> response }.title(shell, ""))
        }
    }

    private fun titleResponse(info: JSONObject): JSONObject = JSONObject().put("code", 0)
        .put("playlist", JSONObject().put("code", 0).put("data", JSONObject().put("dirinfo", info)))

    @Test fun publicPlaylistRequestHasAnExplicitOffsetAndBoundedPageSize() {
        val request = qqPlaylistPageRequest(shell, 1_100, "").getJSONObject("playlist")
        assertEquals("CgiGetDiss", request.getString("method"))
        val params = request.getJSONObject("param")
        assertEquals(12345L, params.getLong("disstid"))
        assertEquals(0, params.getInt("dirid"))
        assertEquals(1_100, params.getInt("song_begin"))
        assertEquals(100, params.getInt("song_num"))
    }

    @Test fun playlistWebCommKeepsFavoriteAuthenticationAndDirectory() {
        val cookie = "uin=123456; qqmusic_key=fixture-key; loginType=2"
        val original = qqPlaybackComm(cookie)
        for (playlist in listOf(shell, shell.copy(id = QQ_FAVORITES_PLAYLIST_ID))) {
            val body = qqPlaylistPageRequest(playlist, 0, cookie)
            val comm = body.getJSONObject("comm")
            assertEquals(19, comm.getInt("ct"))
            assertEquals(0, comm.getInt("cv"))
            assertFalse(comm.has("v"))
            listOf("qq", "authst", "chid", "tmeAppID", "tmeLoginType", "format").forEach { key ->
                assertEquals(original.get(key), comm.get(key))
            }
            val params = body.getJSONObject("playlist").getJSONObject("param")
            assertEquals(if (playlist.id == QQ_FAVORITES_PLAYLIST_ID) 201 else 0, params.getInt("dirid"))
            assertEquals(if (playlist.id == QQ_FAVORITES_PLAYLIST_ID) 0L else 12345L, params.getLong("disstid"))
        }
    }

    @Test fun observedWebShapeKeepsTrackTypeZeroForDeletionInsteadOfSongtypeThirteen() {
        // 只复现设备已确认的字段形态，歌曲身份为合成数据，不保存真实响应。
        val song = JSONObject().put("mid", "0039MnYb0qxYhV").put("id", 456).put("name", "测试歌曲")
            .put("type", 0).put("songtype", 13)
        val data = JSONObject().put("dirinfo", JSONObject().put("title", "测试歌单"))
            .put("songlist", JSONArray().put(song)).put("total_song_num", 1).put("hasmore", 0)
        val track = data.parseQqPlaylistTrackPage(shell, false, 0).playlist.tracks.single()
        assertEquals(0, track.providerType)
        val request = qqPlaylistSongRequest("uin=123456; qqmusic_key=fixture-key", shell.withQqDirectoryId(7), track, false)
            .getJSONObject("req_0")
        assertEquals("DelSonglist", request.getString("method"))
        val info = request.getJSONObject("param").getJSONArray("v_songInfo").getJSONObject(0)
        assertEquals(456L, info.getLong("songId"))
        assertEquals(0, info.getInt("songType"))
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
        val full = collectPlaylistTrackPages { cursor ->
            client.favoritePlaylistPage("uin=123; qqmusic_key=test", "123", false,
                cursor?.offset ?: 0, resolveAccess = false)
        }
        assertEquals(QQ_FAVORITES_PLAYLIST_ID, full.id)
        assertEquals("我喜欢的音乐", full.title)
        assertEquals("喜", full.artworkMark)
        assertEquals(1_462, full.count)
        assertEquals(1_462, full.tracks.size)
        assertEquals("1462", full.tracks.last().catalogId)
    }

    @Test fun repeatedFavoritePageKeepsTheLoadedSongsAndRetryOffset() = runBlocking {
        var repeat = true
        val client = QqLibraryClient(QqPlaylistPaging { body, _ ->
            val offset = body.getJSONObject("playlist").getJSONObject("param").getInt("song_begin")
            favoritesResponse(if (repeat) 0 else offset, 1_462)
        })
        var page = client.favoritePlaylistPage("uin=123; qqmusic_key=test", "123", false, 0, resolveAccess = false)
        val repeated = client.favoritePlaylistPage("uin=123; qqmusic_key=test", "123", false, page.next!!.offset, resolveAccess = false)
        assertTrue(runCatching { mergePlaylistTrackPage(page, repeated) }.isFailure)
        assertEquals(100, page.playlist.tracks.size)
        assertEquals(100, page.next?.offset)
        repeat = false
        while (page.next != null) {
            page = mergePlaylistTrackPage(page, client.favoritePlaylistPage("uin=123; qqmusic_key=test", "123", false,
                page.next!!.offset, resolveAccess = false))
        }
        assertEquals(1_462, page.playlist.tracks.size)
        assertNull(page.next)
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
