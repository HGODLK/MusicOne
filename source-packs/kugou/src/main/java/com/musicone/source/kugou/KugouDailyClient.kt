package com.musicone.source.kugou

import org.json.JSONObject
import java.security.MessageDigest

internal data class KugouDailyResult(
    val tracks: List<MusicTrack>,
    val creationDate: String,
    val multipage: Boolean,
    val subtitle: String,
    val coverUrl: String?,
) {
    val count: Int get() = tracks.size
}

/** 酷狗标准版每日推荐接口（NewDailyBillProtocol）。 */
internal class KugouDailyClient(
    private val send: (String, String, String, Map<String, String>) -> JSONObject = { url, body, cookie, headers ->
        JSONObject(PlatformHttp.postJson(url, body, cookie, headers).text)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val anonymousDevice by lazy { KugouProtocol.createDeviceCookie().cookieValues() }

    fun daily(cookie: String, vip: Boolean, refresh: Boolean = false): KugouDailyResult {
        val identity = anonymousDevice + cookie.cookieValues()
        val time = (clock() / 1_000).toString()
        val params = linkedMapOf(
            "appid" to KUGOU_DAILY_APP_ID,
            "clientver" to KUGOU_DAILY_CLIENT_VERSION,
            "clienttime" to time,
            "platform" to "android",
            "mid" to identity["KUGOU_API_MID"].orEmpty(),
            "dfid" to identity["dfid"].orEmpty().ifBlank { "-" },
            "uuid" to identity["uuid"].orEmpty().ifBlank { "-" },
        ).apply {
            identity["userid"]?.takeIf { it.isNotBlank() && it != "0" }?.let { put("userid", it) }
            identity["token"]?.takeIf(String::isNotBlank)?.let { put("token", it) }
        }
        // 首次拉取直接传空 body，手动刷新时才带 refresh_rec 参数。
        val body = if (refresh) JSONObject()
            .put("appid", KUGOU_DAILY_APP_ID.toInt())
            .put("clientver", KUGOU_DAILY_CLIENT_VERSION.toInt())
            .put("clienttime", time.toLong())
            .put("platform", "android")
            .put("userid", params["userid"]?.toLongOrNull() ?: 0L)
            .put("mid", params.getValue("mid"))
            .put("uuid", params.getValue("uuid"))
            .put("key", KugouProtocol.md5(KUGOU_DAILY_APP_ID + KUGOU_DAILY_SIGN_KEY + KUGOU_DAILY_CLIENT_VERSION + time))
            .put("refresh_rec", 1).toString() else ""
        val url = kugouQueryUrl(KUGOU_DAILY_URL, params + ("signature" to kugouDailySignature(params, body)))
        return send(url, body, cookie, mapOf("mid" to params.getValue("mid"),
            "dfid" to params.getValue("dfid"), "clienttime" to time)).toKugouDailyResult(vip)
    }
}

// 酷狗标准版 20.8.0 的 appid、clientver 和签名 key
internal const val KUGOU_DAILY_APP_ID = "1005"
internal const val KUGOU_DAILY_CLIENT_VERSION = "20809"
internal const val KUGOU_DAILY_SIGN_KEY = "OIlwieks28dk2k092lksi2UIkp"
internal const val KUGOU_DAILY_URL = "https://gateway.kugou.com/everydayrec.service/v3/everyday_song_recommend"

/** 标准版日推签名：对请求体按 UTF-8 字节截取前 256 字节参与计算（不能按字符数截）。 */
internal fun kugouDailySignature(params: Map<String, String>, body: String): String {
    val digest = MessageDigest.getInstance("MD5")
    digest.update(KUGOU_DAILY_SIGN_KEY.toByteArray(Charsets.UTF_8))
    digest.update(params.entries.sortedBy { it.key }.joinToString("") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8))
    digest.update(body.toByteArray(Charsets.UTF_8).let { it.copyOfRange(0, minOf(it.size, 256)) })
    digest.update(KUGOU_DAILY_SIGN_KEY.toByteArray(Charsets.UTF_8))
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun JSONObject.toKugouDailyResult(vip: Boolean): KugouDailyResult {
    requireKugouHomeSuccess()
    val data = optJSONObject("data") ?: throw PlatformApiException("酷狗音乐没有返回每日推荐数据")
    val songs = data.optJSONArray("song_list") ?: data.optJSONArray("info")
        ?: throw PlatformApiException("酷狗音乐没有返回每日推荐歌曲列表")
    val tracks = (0 until songs.length()).map { index ->
        val raw = songs.optJSONObject(index) ?: throw PlatformApiException("酷狗每日推荐歌曲格式不正确")
        raw.toKugouDailyTrack(vip)
    }
    return KugouDailyResult(tracks, data.optString("creation_date"), data.optInt("multipage") > 0,
        data.optString("sub_title"), data.optString("cover_img_url").normalizeKugouContentArtwork())
}

/** 统一标准版日推接口的字段别名，再交给通用歌曲解析器，确保歌曲 ID 生成一致。 */
private fun JSONObject.toKugouDailyTrack(vip: Boolean): MusicTrack {
    val normalized = JSONObject(toString())
    fun alias(target: String, source: String) {
        if ((!normalized.has(target) || normalized.isNull(target) || normalized.optString(target).isBlank()) &&
            has(source) && !isNull(source)) normalized.put(target, get(source))
    }
    alias("hash", "hash_128")
    alias("hq_hash", "hash_320")
    alias("sq_hash", "hash_flac")
    alias("image", "album_cover")
    if (listOf("timelen", "duration", "Duration", "time_length").none { normalized.has(it) && !normalized.isNull(it) }) {
        if (has("timelength")) normalized.put("time_length", get("timelength"))
        else alias("timelen", "timelength_128")
    }
    return normalized.kugouHomeTrack(vip)
}
