package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouRecentReporterTest {
    private val cookie = "userid=42; token=test-token; KUGOU_API_MID=mid"
    private fun track(id: String, owner: String = "42") = SourceTrackDetails(
        SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, owner, "HASH"), "歌曲", opaqueData = mapOf("catalogId" to id)), "kugou-HASH")

    @Test fun sendsMixsongIdentityAndSecondsThroughTheHistoryEndpoint() = runBlocking {
        var calls = 0
        val reporter = KugouRecentReporter(KugouContentClient(KugouContentTransport { request, sent ->
            calls++
            assertEquals(cookie, sent)
            assertEquals("/playhistory/v1/upload_songs", request.path)
            assertEquals("3", request.query["plat"])
            assertNull(request.router)
            val body = requireNotNull(request.body)
            assertEquals("42", body.getString("userid"))
            assertEquals("test-token", body.getString("token"))
            val song = body.getJSONArray("songs").getJSONObject(0)
            assertEquals(77L, song.getLong("mxid"))
            assertEquals(1, song.getInt("op"))
            assertEquals(123456L, song.getLong("ot"))
            assertEquals(1, song.getInt("pc"))
            JSONObject().put("status", 1).put("error_code", 0)
        }), { 123456L })
        reporter.report(track("77"), cookie)
        assertEquals(1, calls)
    }

    @Test fun missingBusinessSuccessIsNotAccepted() = runBlocking {
        val reporter = KugouRecentReporter(KugouContentClient(KugouContentTransport { _, _ -> JSONObject() }))
        try { reporter.report(track("77"), cookie); fail("缺少成功状态应拒绝") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun foreignAccountOrHashCannotBeUsedAsMixsongId() = runBlocking {
        val reporter = KugouRecentReporter(KugouContentClient(KugouContentTransport { _, _ -> error("不应发送") }))
        for (track in listOf(track("HASH"), track("77", "88"))) {
            try { reporter.report(track, cookie); fail("应拒绝错误身份") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
