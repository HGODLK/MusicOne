package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal fun qqSearchRequest(query: String, tab: QqSearchTab, page: Int, size: Int = 30): JSONObject =
    qqSearchModule("music.search.SearchCgiService", "DoSearchForQQMusicDesktop", JSONObject()
        .put("query", query.trim()).put("search_type", tab.type).put("page_num", page)
        .put("num_per_page", size).put("sin", (page - 1) * size).put("ein", page * size)
        .put("highlight", 0).put("cat", 2).put("grp", 1).put("nqc_flag", 0)
        .put("remoteplace", "txt.mqq.all").put("multi_zhida", 0).put("sem", 0))

internal fun qqSearchModule(module: String, method: String, param: JSONObject) = JSONObject()
    .put("module", module).put("method", method).put("param", param)

internal fun JSONObject.qqSearchData(key: String): JSONObject {
    val item = optJSONObject(key) ?: throw PlatformApiException("QQ 音乐没有返回搜索数据")
    val code = item.optInt("code", -1)
    if (optInt("code", -1) != 0 || code != 0) throw PlatformApiException("QQ 音乐搜索请求失败，请重试", code)
    val data = item.optJSONObject("data") ?: throw PlatformApiException("QQ 音乐搜索数据不完整")
    if (data.optInt("code", 0) != 0 || (data.optJSONObject("meta")?.optInt("is_filter", 0) ?: 0) < 0) {
        throw PlatformApiException("QQ 音乐搜索暂时受限，请稍后重试")
    }
    return data
}

internal fun JSONObject.parseQqSearchPage(tab: QqSearchTab, page: Int, vip: Boolean): QqSearchPage {
    val body = optJSONObject("body") ?: throw PlatformApiException("QQ 音乐搜索结果不完整")
    val items = body.optJSONArray(tab.field)
        ?: body.optJSONObject(tab.field)?.optJSONArray("list")
        ?: body.optJSONObject(tab.field)?.optJSONArray("items") ?: JSONArray()
    val values = items.searchObjects()
    val meta = optJSONObject("meta")
    val more = if (meta?.has("nextpage") == true) meta.optInt("nextpage") >= 0
        else page * 30 < (meta?.optInt("sum") ?: optInt("total_num"))
    return QqSearchPage(
        songs = if (tab == QqSearchTab.SONG) values.mapNotNull { value ->
            runCatching { value.toQqTrack(vip) }.getOrNull()
        }.distinctBy { it.id } else emptyList(),
        singers = if (tab == QqSearchTab.SINGER) values.mapNotNull { value ->
            val id = value.searchText("singerMID", "singerMid", "mid")
            val name = value.searchText("singerName", "name")
            if (id.isBlank() || name.isBlank()) null else QqSearchSinger(id, name,
                value.searchText("singerPic", "pic").ifBlank { "https://y.gtimg.cn/music/photo_new/T001R300x300M000$id.jpg" }
                    .replaceFirst("http://", "https://"),
                value.optLong("singerID", value.optLong("singerId", 0L)))
        }.distinctBy { it.id } else emptyList(),
        albums = if (tab == QqSearchTab.ALBUM) values.mapNotNull { it.searchCollection(true) }.distinctBy { it.id } else emptyList(),
        playlists = if (tab == QqSearchTab.PLAYLIST) values.mapNotNull { it.searchCollection(false) }.distinctBy { it.id } else emptyList(),
        page = page, hasMore = more && values.isNotEmpty(),
    )
}

private fun JSONObject.searchCollection(album: Boolean): MusicPlaylist? {
    val id = if (album) searchText("albummid", "albumMID", "albumMid", "mid") else searchText("dissid", "id")
    val title = if (album) searchText("name", "albumName") else searchText("dissname", "name")
    if (id.isBlank() || id == "0" || title.isBlank()) return null
    val colors = qqArtworkColors(id)
    return MusicPlaylist(
        id = (if (album) QQ_SEARCH_ALBUM_PREFIX else "qq-") + id, source = MusicSource.QQ,
        title = title, subtitle = if (album) searchText("singer", "singerName") else
            searchText("nickname").ifBlank { optJSONObject("creator")?.searchText("name").orEmpty() },
        description = listOf(searchText("publish_date", "publicTime"), searchText("description", "introduction"))
            .filter { it.isNotBlank() }.joinToString("\n"),
        count = optInt("song_count", optInt("songnum")), artworkStart = colors.first, artworkEnd = colors.second,
        artworkMark = title.take(1), tracks = emptyList(),
        artworkUrl = searchText("albumPic", "imgurl", "pic", "logo").ifBlank {
            if (album) "https://y.gtimg.cn/music/photo_new/T002R500x500M000$id.jpg" else ""
        }.takeIf { it.isNotBlank() }?.replaceFirst("http://", "https://"),
    )
}

internal fun JSONArray.searchObjects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONObject.searchText(vararg keys: String): String = keys.firstNotNullOfOrNull {
    optString(it).takeIf { value -> value.isNotBlank() && value != "null" }
}?.let { decodeQqPlaylistDescription(it).replace(Regex("<[^>]*>"), "") }.orEmpty()
