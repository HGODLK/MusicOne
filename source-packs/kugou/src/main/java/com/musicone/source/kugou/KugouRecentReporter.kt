package com.musicone.source.kugou

import com.musicone.source.sdk.SourceTrackDetails
import org.json.JSONArray
import org.json.JSONObject

/** 只提交云端历史，复用现有通知入口，不参与播放器和音频请求。 */
internal class KugouRecentReporter(private val client: KugouContentClient = KugouContentClient(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    suspend fun report(track: SourceTrackDetails, cookie: String) {
        val session = cookie.requiredKugouSession()
        require(track.track.identity.sourceId == KUGOU_SOURCE_ID &&
            track.track.identity.accountId?.let { it != session.userId } != true) { "播放历史账号不匹配" }
        val mxid = track.track.opaqueData["catalogId"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("歌曲缺少MixSongID，不能提交云端播放历史")
        val body = JSONObject().put("userid", session.userId).put("token", session.token)
            .put("songs", JSONArray().put(JSONObject().put("mxid", mxid).put("op", 1).put("ot", now()).put("pc", 1)))
        val response = client.load(KugouContentRequest("/playhistory/v1/upload_songs", router = null,
            query = mapOf("plat" to "3"), body = body), cookie, requireData = false)
        require(response.has("status") && response.optInt("status", -1) == 1 &&
            response.optInt("error_code", response.optInt("errcode", 0)) == 0) { "酷狗未确认播放历史提交成功" }
    }
}
