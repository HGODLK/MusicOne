package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONObject

/** 仅实现私人 FM 内容协议；不推测宿主未提供的播放时长或完成状态。 */
internal class KugouRadioClient(
    private val transport: KugouHomeClient = KugouHomeClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun request(cookie: String, action: String, hash: String? = null,
                songId: String? = null, mark: String? = null): JSONObject {
        val values = transport.identity(cookie)
        val time = clock()
        val body = JSONObject().put("appid", KugouProtocol.appId.toInt())
            .put("clienttime", time).put("mid", values["KUGOU_API_MID"])
            .put("action", action).put("recommend_source_locked", 0).put("song_pool_id", 0)
            .put("callerid", 0).put("m_type", 1).put("platform", "ios").put("area_code", 1)
            .put("remain_songcnt", 0).put("clientver", KugouProtocol.clientVersion.toInt())
            .put("is_overplay", 0).put("mode", "normal")
            .put("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a")
            .put("key", KugouProtocol.loginKey(time))
        values["userid"]?.takeIf { it.isNotBlank() && it != "0" }?.let {
            body.put("userid", it.toLongOrNull() ?: it).put("kguid", it.toLongOrNull() ?: it)
        }
        values["token"]?.takeIf(String::isNotBlank)?.let { body.put("token", it) }
        values["vip_type"]?.takeIf { it.isNotBlank() && it != "0" }?.let { body.put("vip_type", it) }
        hash?.takeIf(String::isNotBlank)?.let { body.put("hash", it) }
        songId?.takeIf(String::isNotBlank)?.let { body.put("songid", it) }
        mark?.takeIf(String::isNotBlank)?.let { body.put("cur_mark", it) }
        return transport.post("/v2/personal_recommend", "persnfm.service.kugou.com", cookie, body.toString())
    }
}

internal fun JSONObject.kugouRadioTracks(vip: Boolean, account: String?): List<SourceTrackDetails> {
    requireKugouHomeSuccess()
    val data = optJSONObject("data") ?: throw PlatformApiException("酷狗没有返回猜你喜欢内容")
    val songs = data.optJSONArray("song_list") ?: throw PlatformApiException("酷狗没有返回猜你喜欢歌曲列表")
    return (0 until songs.length()).map { index ->
        val item = songs.optJSONObject(index) ?: throw PlatformApiException("酷狗猜你喜欢歌曲格式不正确")
        val track = KugouSourceMapping.track(item.kugouHomeTrack(vip), account)
        // songid 是 FM 的反馈编号，不得用专辑音频编号替代。
        val songId = item.opt("songid")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
        val mark = item.opt("cur_mark")?.takeUnless { it == JSONObject.NULL }
            ?: data.opt("cur_mark")?.takeUnless { it == JSONObject.NULL }
        val extra = buildMap {
            item.optString("hash").takeIf(String::isNotBlank)?.let { put("kugou.radio.hash", it) }
            if (songId.isNotBlank()) put("kugou.radio.songid", songId)
            if (mark != null) put("kugou.radio.cur_mark", mark.toString())
        }
        track.copy(track = track.track.copy(opaqueData = track.track.opaqueData + extra))
    }
}

internal fun kugouRadioFeedbackAction(action: String): String = when (action) {
    "like" -> "click_red"
    "unlike" -> "cancel_red"
    else -> throw UnsupportedOperationException("酷狗猜你喜欢不支持此反馈：$action")
}

internal fun kugouRadioFirstRequest(previous: SourcePayload?, cookie: String): Boolean {
    if (previous == null || (previous.format.isBlank() && previous.encoded.isBlank())) return true
    require(previous.format == "kugou.radio" && previous.version == 1) { "酷狗猜你喜欢续播信息格式不正确" }
    require(JSONObject(previous.encoded).optString("owner") == KugouProtocol.md5(cookie)) {
        "酷狗账号已变化，请重新进入猜你喜欢"
    }
    return false
}
