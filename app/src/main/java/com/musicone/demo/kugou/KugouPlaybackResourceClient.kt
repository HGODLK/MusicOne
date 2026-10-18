package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

/** 仅在当前档位取址失败时补查，不获取所有档位的播放票据。 */
internal class KugouPlaybackResourceClient {
    private val cache = object : LinkedHashMap<String, Pair<Long, MusicTrack>>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, MusicTrack>>?): Boolean = size > 32
    }

    fun enrich(track: MusicTrack, cookie: String, quality: AudioQuality): MusicTrack {
        val session = cookie.requiredKugouSession()
        val key = "${track.id}:${track.mediaId}:${session.userId}:${session.token.hashCode()}:${quality.name}"
        synchronized(cache) { cache[key] }?.takeIf { System.currentTimeMillis() - it.first < 30_000L }
            ?.let { cached -> return track.copy(
                qualityIds = track.qualityIds + (quality to cached.second.qualityIds.getValue(quality)),
                catalogId = cached.second.catalogId,
                mediaId = cached.second.mediaId,
            ) }
        val params = linkedMapOf(
            "dfid" to session.dfid, "mid" to session.mid, "uuid" to "-",
            "appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
            "clienttime" to (System.currentTimeMillis() / 1_000).toString(),
            "token" to session.token, "userid" to session.userId,
        )
        val body = kugouResourceRequest(track, quality).toString()
        val response = JSONObject(PlatformHttp.postJson(
            KugouProtocol.signedAndroidUrl("https://gateway.kugou.com/v2/get_res_privilege/lite", params, body),
            body, cookie, kugouAndroidHeaders(params, "media.store.kugou.com"),
        ).text)
        if (response.optInt("status", 0) != 1 || response.optInt("error_code", 0) != 0) {
            throw kugouPlaybackApiError(response, "酷狗音乐资源补查失败")
        }
        val enriched = response.withKugouPlaybackResource(track, quality)
        synchronized(cache) { cache[key] = System.currentTimeMillis() to enriched }
        return enriched
    }
}

internal fun kugouResourceRequest(track: MusicTrack, quality: AudioQuality): JSONObject = JSONObject()
    .put("appid", KugouProtocol.appId.toInt())
    .put("clientver", KugouProtocol.clientVersion.toInt())
    .put("area_code", 1).put("behavior", "play")
    .put("need_hash_offset", 1).put("relate", 1).put("support_verify", 1)
    // 此接口携带 album_audio_id 会影响标准档资源返回，只用 hash 和 album_id 补查。
    .put("resource", JSONArray().put(JSONObject()
        .put("type", "audio").put("page_id", 0)
        .put("hash", track.remoteId().lowercase())
        .put("album_id", track.mediaId.ifBlank { "0" })))
    .put("qualities", JSONArray(listOf(quality.kugouQuality())))

internal fun JSONObject.withKugouPlaybackResource(track: MusicTrack, quality: AudioQuality): MusicTrack {
    val data = optJSONArray("data")?.optJSONObject(0) ?: optJSONObject("data")
        ?: throw KugouPlaybackFailure(KugouPlaybackFailureReason.RESOURCE_MISSING, "酷狗音乐未返回歌曲资源")
    val goods = data.optJSONArray("relate_goods")
    val candidates = buildList {
        add(data)
        if (goods != null) for (index in 0 until goods.length()) goods.optJSONObject(index)?.let(::add)
    }
    val item = candidates.firstOrNull {
        (it.optString("quality").kugouQualityFromName() ?: it.optInt("level").kugouQualityFromLevel()) == quality &&
            it.optString("hash").isNotBlank() && it.optString("hash") != "null" &&
            it.optString("hash").any { char -> char != '0' }
    } ?: throw KugouPlaybackFailure(KugouPlaybackFailureReason.RESOURCE_MISSING, "酷狗音乐未返回目标音质资源")
    return track.copy(
        qualityIds = track.qualityIds + (quality to item.getString("hash")),
        catalogId = data.opt("album_audio_id")?.toString()?.takeIf { it != "0" && it != "null" && it.isNotBlank() }
            ?: track.catalogId,
        mediaId = data.opt("album_id")?.toString()?.takeIf { it != "0" && it != "null" && it.isNotBlank() }
            ?: track.mediaId,
    )
}
