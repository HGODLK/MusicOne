package com.musicone.source.kugou

import org.json.JSONObject
import org.json.JSONArray

/** 首页内容请求独立于播放解析；协议核对自 KuGouMusicApi 的每日推荐和 top_playlist。 */
internal class KugouHomeClient(
    private val send: (String, String, String, Map<String, String>) -> JSONObject = { url, body, cookie, headers ->
        JSONObject(PlatformHttp.postJson(url, body, cookie, headers).text)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val anonymousDevice by lazy { KugouProtocol.createDeviceCookie().cookieValues() }

    fun daily(cookie: String, vip: Boolean): List<MusicTrack> =
        post("/everyday_song_recommend", "everydayrec.service.kugou.com", cookie, "",
            mapOf("platform" to "ios")).kugouDailyTracks(vip)

    fun recommendations(cookie: String, vip: Boolean): KugouHomeRecommendations {
        val values = identity(cookie)
        val time = clock()
        val fakem = "60f7ebf1f812edbac3c63a7310001701760f"
        val body = JSONObject().put("appid", KugouProtocol.appId.toInt())
            .put("clientver", KugouProtocol.clientVersion.toInt()).put("platform", "android")
            .put("clienttime", time).put("userid", values["userid"]?.toLongOrNull() ?: 0L)
            .put("key", KugouProtocol.loginKey(time)).put("fakem", fakem).put("area_code", 1)
            .put("mid", values["KUGOU_API_MID"]).put("uuid", "-").put("client_playlist", JSONArray())
            .put("u_info", "a0c35cd40af564444b5584c2754dedec").toString()
        return post("/singlecardrec.service/v1/single_card_recommend", null, cookie, body,
            mapOf("card_id" to "1", "fakem" to fakem, "area_code" to "1", "platform" to "ios"))
            .kugouHomeRecommendations(vip)
    }

    fun playlists(cookie: String, page: Int, pageSize: Int, vip: Boolean): KugouHomePlaylistPage {
        require(page > 0 && pageSize > 0)
        val values = identity(cookie)
        val time = ((clock() + 500) / 1_000).toString()
        val body = JSONObject().put("appid", KugouProtocol.appId.toInt())
            .put("mid", values["KUGOU_API_MID"]).put("clientver", KugouProtocol.clientVersion.toInt())
            .put("platform", "android").put("clienttime", time)
            .put("userid", values["userid"]?.toLongOrNull() ?: 0L)
            .put("module_id", 1).put("page", page).put("pagesize", pageSize)
            .put("key", KugouProtocol.loginKey(time.toLong()))
            .put("special_recommend", JSONObject().put("withtag", 1).put("withsong", 1)
                .put("sort", 1).put("ugc", 1).put("is_selected", 0).put("withrecommend", 1)
                .put("area_code", 1).put("categoryid", 0))
            .put("req_multi", 1).put("retrun_min", 5).put("return_special_falg", 1).toString()
        return post("/v2/special_recommend", "specialrec.service.kugou.com", cookie, body)
            .kugouHomePlaylists(page, pageSize, vip)
    }

    internal fun identity(cookie: String): Map<String, String> = anonymousDevice + cookie.cookieValues()

    internal fun post(path: String, router: String?, cookie: String, body: String,
                     extra: Map<String, String> = emptyMap()): JSONObject {
        val values = identity(cookie)
        val params = linkedMapOf("dfid" to values["dfid"].orEmpty().ifBlank { "-" },
            "mid" to values["KUGOU_API_MID"].orEmpty(), "uuid" to "-",
            "appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
            "clienttime" to (clock() / 1_000).toString()).apply {
            values["token"]?.takeIf(String::isNotBlank)?.let { put("token", it) }
            values["userid"]?.takeIf { it.isNotBlank() && it != "0" }?.let { put("userid", it) }
            putAll(extra)
        }
        val response = send(KugouProtocol.signedAndroidUrl("https://gateway.kugou.com$path", params, body),
            body, cookie, kugouAndroidHeaders(params, router))
        response.requireKugouHomeSuccess()
        return response
    }
}

internal fun JSONObject.requireKugouHomeSuccess() {
    val error = optInt("error_code", optInt("errcode", 0))
    if (optInt("status", 1) != 1 || error != 0) {
        throw PlatformApiException(optString("error_msg").ifBlank { optString("msg") }
            .ifBlank { "酷狗推荐请求失败，请重试" }, error)
    }
}
