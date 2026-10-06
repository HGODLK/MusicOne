package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.parseQqDailyTracks(hasVipAccess: Boolean): List<MusicTrack> {
    val data = optJSONObject("req_0")?.optJSONObject("data") ?: return emptyList()
    return data.optJSONArray("songlist").qqPersonalizedObjects { item ->
        (item.optJSONObject("track")
            ?: item.optJSONObject("songInfo")
            ?: item.optJSONObject("song")
            ?: item.optJSONObject("data")
            ?: item).toQqTrack(hasVipAccess)
    }.distinctBy(MusicTrack::id)
}

internal fun JSONObject.parseQqPersonalizedPlaylists(): List<MusicPlaylist> {
    val values = optJSONObject("req_0")?.optJSONObject("data")?.optJSONArray("List") ?: return emptyList()
    return values.qqPersonalizedObjects { item ->
        val basic = item.optJSONObject("Playlist")?.optJSONObject("basic")
            ?: item.optJSONObject("playlist")?.optJSONObject("basic")
            ?: throw IllegalArgumentException("无效推荐歌单")
        val remoteId = basic.qqFirstText("tid", "dissid", "id")
        val title = decodeQqPlaylistDescription(basic.qqFirstText("title", "dissname", "name"))
        if (remoteId.isBlank() || title.isBlank()) throw IllegalArgumentException("无效推荐歌单")
        val creator = basic.optJSONObject("creator")
        val colors = qqArtworkColors(title)
        MusicPlaylist(
            id = "qq-$remoteId",
            source = MusicSource.QQ,
            title = title,
            subtitle = decodeQqPlaylistDescription(
                creator?.qqFirstText("nick", "nickname", "name").orEmpty(),
            ).ifBlank { "QQ 音乐推荐" },
            description = decodeQqPlaylistDescription(basic.qqFirstText("desc", "description")),
            count = basic.qqFirstPositiveInt("song_cnt", "song_count", "songnum"),
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = title.take(1),
            tracks = emptyList(),
            artworkUrl = basic.qqCoverUrl(),
        )
    }.distinctBy(MusicPlaylist::id)
}

internal fun JSONObject.qqCoverUrl(): String? {
    val nested = optJSONObject("cover")
    val flatCover = opt("cover")?.takeIf { it is String }?.toString()
    val raw = listOf(
        nested?.qqFirstText("medium_url", "default_url", "big_url", "small_url", "url"),
        qqFirstText("diss_cover", "cover_url", "coverUrl", "logo", "imgurl", "picurl", "PicUrl"),
        flatCover,
    ).firstNotNullOfOrNull { it?.takeIf(String::isNotBlank) }
    return raw?.qqHttpsUrl()
}

private fun JSONObject.qqFirstText(vararg names: String): String = names.firstNotNullOfOrNull { name ->
    if (!has(name) || isNull(name)) return@firstNotNullOfOrNull null
    opt(name)?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "0" }
}.orEmpty()

private fun JSONObject.qqFirstPositiveInt(vararg names: String): Int = names.firstNotNullOfOrNull { name ->
    optInt(name).takeIf { it > 0 }
} ?: 0

private fun <T> JSONArray?.qqPersonalizedObjects(transform: (JSONObject) -> T): List<T> = buildList {
    val values = this@qqPersonalizedObjects ?: return@buildList
    for (index in 0 until values.length()) {
        values.optJSONObject(index)?.let { runCatching { transform(it) }.getOrNull() }?.let(::add)
    }
}
