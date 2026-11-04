package com.musicone.source.kugou

import com.musicone.source.sdk.SourcePayload
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouRadioDataTest {
    @Test fun radioUsesActualPersonalProtocolWithoutInventingPlaybackEvidence() {
        var sent: JSONObject? = null
        val transport = KugouHomeClient(send = { url, body, _, headers ->
            assertTrue(url.contains("/v2/personal_recommend?"))
            assertEquals("persnfm.service.kugou.com", headers["x-router"])
            sent = JSONObject(body)
            JSONObject("""{"status":1,"data":{"song_list":[]}}""")
        }, clock = { 1_234_000 })
        KugouRadioClient(transport, clock = { 1_234_000 }).request("userid=7; token=a; KUGOU_API_MID=123", "login")
        val data = sent!!
        assertEquals("login", data.getString("action"))
        assertEquals("normal", data.getString("mode"))
        assertEquals(0, data.getInt("remain_songcnt"))
        assertEquals(0, data.getInt("is_overplay"))
        assertFalse(data.has("playtime"))
        assertFalse(data.has("hash"))
        assertFalse(data.has("songid"))
        assertEquals(7L, data.getLong("userid"))
    }

    @Test fun fmFeedbackIdentityStaysSeparateFromPlaybackCatalogIdentity() {
        val response = JSONObject("""{"status":1,"data":{"cur_mark":"batch-mark","song_list":[
            {"hash":"normal","hq_hash":"high","songid":12,"mixsongid":99,"songname":"歌曲",
            "author_name":"歌手","time_length":181}]}}""")
        val track = response.kugouRadioTracks(false, "7").single()
        assertEquals("kugou-normal", track.stableId)
        assertEquals("99", track.track.opaqueData["catalogId"])
        assertEquals("12", track.track.opaqueData["kugou.radio.songid"])
        assertEquals("batch-mark", track.track.opaqueData["kugou.radio.cur_mark"])
        assertEquals(181000L, track.durationMs)
        assertEquals("normal", KugouSourceMapping.track(track).qualityIds[AudioQuality.STANDARD])
        assertEquals("high", KugouSourceMapping.track(track).qualityIds[AudioQuality.EXHIGH])
    }

    @Test fun feedbackPassesActualHashAndMarkWithoutCreatingDuration() {
        var sent = JSONObject()
        val transport = KugouHomeClient(send = { _, body, _, _ ->
            sent = JSONObject(body)
            JSONObject("""{"status":1}""")
        })
        KugouRadioClient(transport).request("", kugouRadioFeedbackAction("like"), "actual-hash", "12", "mark")
        assertEquals("click_red", sent.getString("action"))
        assertEquals("actual-hash", sent.getString("hash"))
        assertEquals("12", sent.getString("songid"))
        assertEquals("mark", sent.getString("cur_mark"))
        assertFalse(sent.has("playtime"))
        assertEquals("cancel_red", kugouRadioFeedbackAction("unlike"))
        try { kugouRadioFeedbackAction("skip"); fail("未知反馈不得伪造成功") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun continuationRejectsAnotherAccountAndDoesNotContainCredentials() {
        assertTrue(kugouRadioFirstRequest(null, "userid=7; token=a"))
        val encoded = JSONObject().put("owner", KugouProtocol.md5("userid=7; token=a")).toString()
        assertFalse(encoded.contains("token"))
        val payload = SourcePayload("kugou.radio", 1, encoded)
        assertFalse(kugouRadioFirstRequest(payload, "userid=7; token=a"))
        try { kugouRadioFirstRequest(payload, "userid=8; token=b"); fail("换号必须隔离续播") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun failedOrMalformedRadioResponseCannotBecomeSuccessfulEmptyBatch() {
        listOf("""{"status":0,"data":{"song_list":[]}}""", """{"status":1,"data":{}}""")
            .forEach { json ->
                try { JSONObject(json).kugouRadioTracks(false, null); fail("必须报告错误") }
                catch (_: PlatformApiException) { }
            }
    }
}
