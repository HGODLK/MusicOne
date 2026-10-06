package com.musicone.demo

import org.json.JSONObject

internal data class ArtistSearchPage(
    val items: List<MusicTrack>,
    val nextOffset: Int?,
    val total: Int,
    val customInfo: JSONObject,
)

internal fun qqArtistSearchCustomInfo(
    singerId: Long,
    tabId: String,
    count: Int,
    sort: ArtistSongSort,
    excludedSongIds: List<String>,
): JSONObject = JSONObject()
    .put("SingerID", singerId.toString())
    .put("TabID", tabId)
    .put("Count", count.toString())
    .put("SortType", sort.order.toString())
    .put("filter_song", excludedSongIds.mapNotNull { id ->
        id.toLongOrNull()?.takeIf { it > 0L }?.toString()
    }.distinct().joinToString(","))

internal fun qqArtistSongSearchRequest(
    query: String,
    offset: Int,
    customInfo: JSONObject,
): JSONObject = qqSearchModule(
    "music.adaptor.searchSvr",
    "DoSearch",
    JSONObject()
        .put("search_source", 7)
        .put("search_type", 2)
        .put("searchkey", query.trim())
        .put("search_id", "")
        .put("remoteplace", if (offset == 0) {
            "search.android.keyboard.singersonglist"
        } else {
            "more.android.keyboard.singersonglist"
        })
        .put("offset", offset)
        .put("custom_info", customInfo),
)

internal fun JSONObject.parseQqArtistSearchPage(
    singerMid: String,
    vip: Boolean,
): ArtistSearchPage {
    val code = optInt("ret_code", -1)
    if (code != 0) throw PlatformApiException("QQ 音乐歌手歌曲搜索失败，请重试", code)
    val values = optJSONObject("data")?.optJSONArray("track_items")?.searchObjects().orEmpty()
    val tracks = values.mapNotNull { value ->
        runCatching {
            (value.optJSONObject("songInfo") ?: value.optJSONObject("track") ?: value).toQqTrack(vip)
        }.getOrNull()
    }.filter { track -> track.artistRefs.any { artist -> artist.mid == singerMid } }
        .distinctBy { it.id }
    val next = optInt("next_offset", -1).takeIf { it >= 0 }
    val custom = optJSONObject("custom_info")?.let { JSONObject(it.toString()) } ?: JSONObject()
    return ArtistSearchPage(tracks, next, optInt("estimate_num", tracks.size), custom)
}
