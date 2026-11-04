package com.musicone.source.kugou

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class KugouDailyClientTest {
    @Test fun standardDailyUsesRealSessionAndEmptyBodyWhileRefreshSignsItsOwnJson() {
        val bodies = mutableListOf<String>()
        val session = "userid=7; token=test-session; KUGOU_API_MID=123; dfid=device"
        val client = KugouDailyClient(send = { url, body, cookie, headers ->
            val uri = URI(url)
            assertEquals("https", uri.scheme)
            assertEquals("gateway.kugou.com", uri.host)
            assertEquals("/everydayrec.service/v3/everyday_song_recommend", uri.path)
            val query = uri.rawQuery.split('&').associate { pair ->
                val parts = pair.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            assertEquals("1005", query["appid"])
            assertEquals("20809", query["clientver"])
            assertEquals("android", query["platform"])
            assertEquals("1234", query["clienttime"])
            assertEquals("7", query["userid"])
            assertEquals("test-session", query["token"])
            assertEquals("123", query["mid"])
            assertEquals("device", query["dfid"])
            assertEquals(session, cookie)
            assertFalse(headers.containsKey("x-router"))
            assertEquals(kugouDailySignature(query - "signature", body), query["signature"])
            bodies += body
            JSONObject("""{"status":1,"error_code":0,"data":{"song_list":[]}}""")
        }, clock = { 1_234_000L })
        client.daily(session, true)
        client.daily(session, true, refresh = true)
        assertEquals("", bodies[0])
        val refresh = JSONObject(bodies[1])
        assertEquals(1, refresh.getInt("refresh_rec"))
        assertEquals(7L, refresh.getLong("userid"))
        assertEquals(1234L, refresh.getLong("clienttime"))
        assertEquals(KugouProtocol.md5("1005OIlwieks28dk2k092lksi2UIkp208091234"), refresh.getString("key"))
        assertFalse(refresh.has("u_info"))
        assertFalse(refresh.has("vip_type"))
        assertFalse(refresh.has("rec_vip_info"))
    }

    @Test fun nativeSignatureUsesSortedRawParametersAndExactly256Utf8Bytes() {
        val query = linkedMapOf("z" to "9", "a" to "中文")
        assertEquals("998ad719e1259f89a52e4637d93c33db", kugouDailySignature(query, ""))
        assertEquals("c0060df84d56d00e63299224cad478cf", kugouDailySignature(query, "歌".repeat(100) + "tail"))
        assertEquals(kugouDailySignature(query, "歌".repeat(100)),
            kugouDailySignature(query, "歌".repeat(100) + "different-tail"))
        assertNotEquals(kugouDailySignature(query, ""), kugouDailySignature(query, "{}"))
    }

    @Test fun completeListRetainsServerOrderAndActualCountBeyondThirty() {
        val songs = JSONArray()
        repeat(47) { index -> songs.put(JSONObject().put("hash_128", "hash-$index")
            .put("hash_320", "high-$index").put("hash_flac", "flac-$index")
            .put("filename", "歌手 - 歌曲$index").put("album_audio_id", index + 1)
            .put("album_id", 9).put("time_length", 180.25)) }
        val result = JSONObject().put("status", 1).put("data", JSONObject().put("song_list", songs)
            .put("multipage", 1).put("creation_date", "2026-10-23").put("sub_title", "今日推荐"))
            .toKugouDailyResult(false)
        assertEquals(47, result.count)
        assertEquals((0 until 47).map { "kugou-hash-$it" }, result.tracks.map { it.id })
        assertEquals("1", result.tracks.first().catalogId)
        assertEquals("9", result.tracks.first().mediaId)
        assertEquals(180250L, result.tracks.first().durationMs)
        assertEquals("high-0", result.tracks.first().qualityIds[AudioQuality.EXHIGH])
        assertEquals("flac-0", result.tracks.first().qualityIds[AudioQuality.LOSSLESS])
        assertTrue(result.multipage)
        assertEquals("2026-10-23", result.creationDate)
    }

    @Test fun officialInfoFallbackPreservesHashIdentityAndBothObservedDurationUnits() {
        val result = JSONObject("""{"status":1,"data":{"info":[
            {"hash_128":"first","filename":"歌手 - 首曲","album_audio_id":7,"timelength":180},
            {"hash":"existing","hash_128":"standard","filename":"歌手 - 次曲","album_audio_id":8,"timelength_128":180321}
        ]}}""").toKugouDailyResult(false)
        assertEquals(listOf("kugou-first", "kugou-existing"), result.tracks.map { it.id })
        assertEquals(listOf(180000L, 180321L), result.tracks.map { it.durationMs })
        assertEquals(2, result.count)
    }

    @Test fun missingOrRejectedResponseIsNeverDisguisedAsEmptyDailyList() {
        for (raw in listOf("""{"status":0,"error_code":20010,"data":{"song_list":[]}}""",
            """{"status":1,"data":{}}""", """{"status":1,"data":{"song_list":[null]}}""")) {
            try {
                JSONObject(raw).toKugouDailyResult(false)
                fail("无效响应不能转换为空日推")
            } catch (_: PlatformApiException) { }
        }
        assertEquals(0, JSONObject("""{"status":1,"data":{"song_list":[]}}""")
            .toKugouDailyResult(false).count)
    }
}
