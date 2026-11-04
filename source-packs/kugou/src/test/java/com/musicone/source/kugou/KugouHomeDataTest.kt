package com.musicone.source.kugou

import com.musicone.source.sdk.SourceCursorRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouHomeDataTest {
    @Test fun dailyUsesDedicatedProtocolAndPreservesSongResources() {
        val requests = mutableListOf<String>()
        val client = KugouHomeClient(send = { url, body, _, headers ->
            requests += url
            assertEquals("", body)
            assertEquals("everydayrec.service.kugou.com", headers["x-router"])
            JSONObject("""{"status":1,"data":{"song_list":[{"hash":"normal","hq_hash":"high",
                "album_audio_id":42,"ori_audio_name":"推荐歌曲","author_name":"歌手",
                "sizable_cover":"https://img.kugou.com/{size}/test.jpg","time_length":180}]}}""")
        }, clock = { 1_234_000 })
        val track = client.daily("userid=7; token=session; KUGOU_API_MID=123", false).single()
        assertTrue(requests.single().contains("/everyday_song_recommend?"))
        assertTrue(requests.single().contains("platform=ios"))
        assertEquals("kugou-normal", track.id)
        assertEquals("42", track.catalogId)
        assertEquals("normal", track.qualityIds[AudioQuality.STANDARD])
        assertEquals("high", track.qualityIds[AudioQuality.EXHIGH])
        assertEquals(180000L, track.durationMs)
        assertEquals("推荐歌曲", track.title)
        assertEquals("https://img.kugou.com/500/test.jpg", track.artworkUrl)
    }

    @Test fun playlistsSendActualPageAndRequestedSize() {
        val pages = mutableListOf<Int>()
        val client = KugouHomeClient(send = { url, body, _, headers ->
            assertTrue(url.contains("/v2/special_recommend?"))
            assertEquals("specialrec.service.kugou.com", headers["x-router"])
            val data = JSONObject(body)
            pages += data.getInt("page")
            assertEquals(9, data.getInt("pagesize"))
            assertEquals(0, data.getJSONObject("special_recommend").getInt("categoryid"))
            JSONObject("""{"status":1,"data":{"special_list":[{"specialid":123,"specialname":"推荐歌单"}]}}""")
        }, clock = { 1_234_000 })
        assertEquals(2, client.playlists("", 1, 9, false).nextPage)
        assertEquals("kugou-123", client.playlists("", 2, 9, false).playlists.single().id)
        assertEquals(listOf(1, 2), pages)
    }

    @Test fun paginationEndsOnlyOnExplicitEvidence() {
        fun response(extra: String = "", list: String = "[{\"specialid\":1}]") =
            JSONObject("""{"status":1,"data":{"special_list":$list$extra}}""")
        assertEquals(2, response().kugouHomePlaylists(1, 20, false).nextPage)
        assertNull(response(list = "[]").kugouHomePlaylists(2, 20, false).nextPage)
        assertNull(response(",\"has_more\":0").kugouHomePlaylists(1, 20, false).nextPage)
        assertNull(response(",\"has_next\":0").kugouHomePlaylists(1, 20, false).nextPage)
        assertEquals(2, response(",\"has_next\":1").kugouHomePlaylists(1, 20, false).nextPage)
        assertNull(response(",\"total\":1").kugouHomePlaylists(1, 20, false).nextPage)
    }

    @Test fun independentSongRecommendationsMatchObservedLiteProtocolAndPreserveDecimalSeconds() {
        var sent = JSONObject()
        val client = KugouHomeClient(send = { url, body, cookie, headers ->
            assertTrue(url.contains("/singlecardrec.service/v1/single_card_recommend?"))
            assertTrue(url.contains("card_id=1"))
            assertTrue(url.contains("platform=ios"))
            assertFalse(headers.containsKey("x-router"))
            assertEquals("userid=7; token=session; KUGOU_API_MID=123", cookie)
            sent = JSONObject(body)
            JSONObject("""{"status":1,"data":{"rec_desc":"「精选好歌随心听」","song_list_size":1,
                "song_list":[{"hash":"normal","hash_128":"normal","hash_320":"high","hash_flac":"lossless",
                "mixsongid":"99","album_id":12,"songname":"真实歌曲","author_name":"真实歌手",
                "singerinfo":[{"id":7,"name":"真实歌手"}],"time_length":290.037,
                "sizable_cover":"https://img.kugou.com/{size}/cover.jpg"}]}}""")
        }, clock = { 1_234_000 })
        val result = client.recommendations("userid=7; token=session; KUGOU_API_MID=123", false)
        assertEquals(3116, sent.getInt("appid"))
        assertEquals(11440, sent.getInt("clientver"))
        assertEquals("123", sent.getString("mid"))
        assertEquals(7L, sent.getLong("userid"))
        assertEquals(0, sent.getJSONArray("client_playlist").length())
        assertEquals("「精选好歌随心听」", result.title)
        val track = result.tracks.single()
        assertEquals("kugou-normal", track.id)
        assertEquals("99", track.catalogId)
        assertEquals(290037L, track.durationMs)
        assertEquals("high", track.qualityIds[AudioQuality.EXHIGH])
        assertEquals("lossless", track.qualityIds[AudioQuality.LOSSLESS])
        assertEquals("7", track.artistReferences.single().id)
    }

    @Test fun globalIdentifiersNeverMasqueradeAsSpecialIdentifiers() {
        val page = JSONObject("""{"status":1,"data":{"special_list":[
            {"specialid":123,"global_collection_id":"gid-a","list_create_userid":7,"list_create_listid":9},
            {"global_collection_id":"456","list_create_userid":8,"list_create_listid":10},
            {"global_collection_id":"gid-b"},{"list_create_gid":"gid-c"}]}}""")
            .kugouHomePlaylists(1, 20, false)
        assertEquals(listOf("kugou-123", "kugou-global:456", "kugou-global:gid-b", "kugou-global:gid-c"),
            page.playlists.map { it.id })
        assertEquals("gid-a", page.metadata["kugou-123"]?.get("global_collection_id"))
        assertEquals("7", page.metadata["kugou-123"]?.get("list_create_userid"))
        assertEquals("9", page.metadata["kugou-123"]?.get("list_create_listid"))
        assertEquals("global", page.metadata["kugou-global:456"]?.get("collection_route"))
        assertEquals("456", page.metadata["kugou-global:456"]?.get("global_collection_id"))
        assertEquals("gid-c", page.metadata["kugou-global:gid-c"]?.get("global_collection_id"))
    }

    @Test fun statusZeroCannotBecomeSuccessfulEmptyRecommendations() {
        try {
            JSONObject("""{"status":0,"data":{"song_list":[]}}""").kugouDailyTracks(false)
            fail("必须传播推荐接口失败")
        } catch (_: PlatformApiException) { }
    }

    @Test fun cursorBindsAccountAndKeepsOriginalPageSize() {
        val cursor = KugouHomeCursor.read(SourceCursorRequest(limit = 9), "userid=7; token=a")
            .copy(page = 2).encode()
        assertEquals(9, KugouHomeCursor.read(SourceCursorRequest(cursor, 40), "userid=7; token=a").pageSize)
        try {
            KugouHomeCursor.read(SourceCursorRequest(cursor), "userid=8; token=b")
            fail("其他账号不能使用旧游标")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun invalidRecommendationRowsAreNotSilentlyDropped() {
        try {
            JSONObject("""{"status":1,"data":{"song_list":[{}]}}""").kugouDailyTracks(false)
            fail("无效内容应报告错误")
        } catch (_: PlatformApiException) { }
    }
}
