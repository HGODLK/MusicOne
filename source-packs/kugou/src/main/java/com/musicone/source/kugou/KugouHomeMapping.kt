package com.musicone.source.kugou

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

internal data class KugouHomePlaylistPage(val playlists: List<MusicPlaylist>, val nextPage: Int?,
    val metadata: Map<String, Map<String, String>> = emptyMap())
internal data class KugouHomeRecommendations(val title: String, val tracks: List<MusicTrack>)

/** 仅补齐推荐协议的字段别名，歌曲身份与音质仍由既有映射决定。 */
internal fun JSONObject.kugouHomeTrack(vip: Boolean): MusicTrack {
    val normalized = JSONObject(toString())
    fun alias(target: String, source: String) {
        if ((!normalized.has(target) || normalized.isNull(target)) && has(source) && !isNull(source)) {
            normalized.put(target, get(source))
        }
    }
    alias("songname", "ori_audio_name")
    alias("image", "sizable_cover")
    // 推荐接口实证为秒且可带小数；先换成毫秒，避免旧整数读取丢失小数部分。
    if (listOf("timelen", "Duration", "duration").none { normalized.has(it) && !normalized.isNull(it) }) {
        opt("time_length")?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            ?.let { normalized.put("timelen", (it * 1_000).roundToLong()) }
    }
    alias("singers", "singerinfo")
    val track = normalized.enrichKugouContentTrack(normalized.toKugouTrack(vip))
    // top/card 使用 hash_128/320/flac；仅补缺失档位，不改变已有身份与音质选择。
    val hashes = track.qualityIds.toMutableMap()
    listOf(AudioQuality.STANDARD to "hash_128", AudioQuality.EXHIGH to "hash_320", AudioQuality.LOSSLESS to "hash_flac")
        .forEach { (quality, key) ->
            normalized.homeString(key).takeIf { it.any { char -> char != '0' } }
                ?.let { hash -> if (quality !in hashes) hashes[quality] = hash }
        }
    return track.copy(qualityIds = hashes)
}

internal fun JSONObject.kugouHomeRecommendations(vip: Boolean): KugouHomeRecommendations {
    requireKugouHomeSuccess()
    val data = optJSONObject("data") ?: throw PlatformApiException("酷狗音乐没有返回歌曲推荐")
    val songs = data.optJSONArray("song_list") ?: throw PlatformApiException("酷狗音乐没有返回推荐歌曲列表")
    val title = data.homeString("rec_desc").ifBlank { "为你推荐" }
    return KugouHomeRecommendations(title, songs.homeObjects { it.kugouHomeTrack(vip) })
}

internal fun JSONObject.kugouDailyTracks(vip: Boolean): List<MusicTrack> {
    requireKugouHomeSuccess()
    val songs = optJSONObject("data")?.optJSONArray("song_list")
        ?: throw PlatformApiException("酷狗音乐没有返回每日推荐歌曲")
    return songs.homeObjects { it.kugouHomeTrack(vip) }
}

internal fun JSONObject.kugouHomePlaylists(page: Int, pageSize: Int, vip: Boolean): KugouHomePlaylistPage {
    requireKugouHomeSuccess()
    val data = optJSONObject("data") ?: throw PlatformApiException("酷狗音乐没有返回推荐歌单")
    val raw = data.optJSONArray("special_list") ?: throw PlatformApiException("酷狗音乐没有返回推荐歌单列表")
    val metadata = mutableMapOf<String, Map<String, String>>()
    val lists = raw.homeObjects { item ->
        // 全局编号即使全是数字，也不能冒充旧 specialid 送入歌单详情。
        val specialId = item.homeString("specialid", "special_id").takeIf { value -> value.all(Char::isDigit) }
            .orEmpty()
        val globalId = item.homeString("global_collection_id", "list_create_gid")
        if (specialId.isBlank() && globalId.isBlank()) throw PlatformApiException("酷狗推荐歌单缺少编号")
        val id = if (specialId.isNotBlank()) "kugou-$specialId" else "kugou-global:$globalId"
        val title = item.homeString("specialname", "name").ifBlank { "酷狗音乐歌单" }
        metadata[id] = buildMap {
            listOf("list_create_userid", "list_create_listid", "global_collection_id", "list_create_gid")
                .forEach { key -> item.homeString(key).takeIf(String::isNotBlank)?.let { put(key, it) } }
            if (globalId.isNotBlank()) put("global_collection_id", globalId)
            if (specialId.isBlank()) put("collection_route", "global")
        }
        MusicPlaylist(id = id, title = title,
            subtitle = item.homeString("nickname", "username").ifBlank { "酷狗音乐推荐" },
            description = item.homeString("intro"), count = item.optInt("songcount", item.optInt("count", 0)),
            artworkMark = title.take(1), artworkUrl = item.homeString("flexible_cover", "imgurl", "pic")
                .takeIf(String::isNotBlank)?.replace("{size}", "500")?.replaceFirst("http://", "https://"),
            tracks = item.optJSONArray("songs")?.homeObjects { it.kugouHomeTrack(vip) }.orEmpty())
    }
    // 不轮换同一个卡池；缺少明确结束标记时继续请求真实下一页，空页自然结束。
    val total = sequenceOf("total", "total_count").firstOrNull { data.has(it) && !data.isNull(it) }
        ?.let { data.optLong(it, -1L).takeIf { count -> count >= 0 } }
    val hasMore = when {
        raw.length() == 0 -> false
        data.has("has_next") -> data.optInt("has_next", 0) != 0 || data.optBoolean("has_next", false)
        data.has("has_more") -> data.optInt("has_more", 0) != 0 || data.optBoolean("has_more", false)
        total != null -> page.toLong() * pageSize < total
        else -> true
    }
    return KugouHomePlaylistPage(lists, if (hasMore) Math.addExact(page, 1) else null, metadata)
}

private fun JSONObject.homeString(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    if (has(key) && !isNull(key)) opt(key)?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "0" } else null
}.orEmpty()

private fun <T> JSONArray.homeObjects(convert: (JSONObject) -> T): List<T> = (0 until length()).map { index ->
    val item = optJSONObject(index) ?: throw PlatformApiException("酷狗推荐数据格式不正确")
    convert(item)
}
