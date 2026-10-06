package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QqRecentPlayTest {
    @Test fun requestUsesOfficialRecentSongFolderAndCountModules() {
        val request = qqRecentPlayRequest()
        assertEquals(2, request.getJSONObject("req_0").getJSONObject("param").getInt("type"))
        assertEquals(4, request.getJSONObject("req_1").getJSONObject("param").getInt("type"))
        assertEquals("GetPlayRecentlyCount", request.getJSONObject("req_2").getString("method"))
        assertEquals(500, request.getJSONObject("req_0").getJSONObject("param").getInt("requestCnt"))
    }

    @Test fun reportUsesOfficialRecentPlayWriteContract() {
        val track = recentTrack("测试歌曲", "00123456789012", 123)
        val request = qqRecentPlayReportRequest(track, lastTimeSeconds = 456, listenCount = 2)
        val module = request.getJSONObject("req_0")
        val item = module.getJSONObject("param").getJSONArray("data").getJSONObject(0)

        assertEquals("music.musicasset.PlayRecentlyWrite", module.getString("module"))
        assertEquals("ReportPlayRecentlyInfo", module.getString("method"))
        assertEquals("123", item.getString("id"))
        assertEquals(2, item.getInt("type"))
        assertEquals(456L, item.getLong("lastTime"))
        assertEquals(2, item.getInt("listenCnt"))
        assertEquals("00123456789012", item.getString("auxillaryID"))
    }

    @Test fun musicFlowShortCardWaitsForMidBeforeRecentPlayReport() {
        val shortCard = MusicTrack(
            id = "qq-123",
            source = MusicSource.QQ,
            title = "测试歌曲",
            artists = "测试歌手",
            album = "",
            durationMs = 0L,
            artworkStart = 0L,
            artworkEnd = 0L,
            artworkMark = "测",
            previewUrl = "",
            catalogId = "123",
        )

        assertFalse(shortCard.hasQqRecentPlayIdentity())
        assertTrue(shortCard.copy(songMid = "00123456789012").hasQqRecentPlayIdentity())
    }

    @Test fun responseKeepsRecentTimeAndFolderIndexOrderWithOfficialCount() {
        val response = JSONObject().put("code", 0)
            .put("req_0", responseData(JSONObject().put("songList", JSONArray()
                .put(recentSong("旧歌", "00123456789012", 123, 10))
                .put(recentSong("新歌", "00123456789013", 124, 20)))))
            .put("req_1", responseData(JSONObject().put("geDanList", JSONArray()
                .put(folder("第二张", 2, 2))
                .put(folder("第一张", 1, 1)))))
            .put("req_2", JSONObject().put("code", 0).put("data", JSONObject().put("code", 0)
                .put("counts", JSONObject().put("123", JSONObject()).put("124", JSONObject())
                    .put("125", JSONObject()))))
        val parsed = parseQqRecentPlay(response, hasVipAccess = true)
        assertEquals(listOf("新歌", "旧歌"), parsed.songs.map(MusicTrack::title))
        assertEquals(3, parsed.songCount)
        assertEquals(listOf("第一张", "第二张"), parsed.playlists.map(MusicPlaylist::title))
        assertEquals("qq-1", parsed.playlists.first().id)
        assertTrue(parsed.asRecentPlaylists().first().tracks.isNotEmpty())
        assertFalse(shouldCachePlaylistDetail(parsed.asRecentPlaylists().first()))
        assertTrue(shouldCachePlaylistDetail(parsed.playlists.first()))
    }

    private fun responseData(data: JSONObject) = JSONObject().put("code", 0)
        .put("data", JSONObject().put("code", 0).put("data", data))

    private fun recentSong(title: String, mid: String, id: Int, lastTime: Long) = JSONObject()
        .put("lastTime", lastTime)
        .put("listenCnt", 1)
        .put("track", JSONObject().put("mid", mid).put("id", id).put("name", title)
            .put("interval", 180).put("album", JSONObject().put("name", "专辑").put("mid", "00000000000001"))
            .put("singer", JSONArray().put(JSONObject().put("name", "歌手").put("mid", "00000000000002"))))

    private fun recentTrack(title: String, mid: String, id: Int): MusicTrack =
        recentSong(title, mid, id, 1).getJSONObject("track").toQqTrack()

    private fun folder(title: String, id: Int, index: Int) = JSONObject()
        .put("name", title).put("id", id).put("index", index).put("num", 30)
        .put("lastTime", 100 - index).put("pic", "https://example.com/$id.jpg")
}
