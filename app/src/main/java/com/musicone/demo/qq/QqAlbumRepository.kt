package com.musicone.demo

import org.json.JSONObject

/** 专辑复用歌单展示模型，但始终使用专辑接口和原始曲序。 */
internal class QqAlbumRepository {
    fun load(album: MusicPlaylist, cookie: String, vip: Boolean): MusicPlaylist {
        val mid = album.id.removePrefix(QQ_SEARCH_ALBUM_PREFIX)
        fun request(module: String, method: String, params: JSONObject): JSONObject {
            val comm = JSONObject().apply { qqPersonalizedCommValues(cookie).forEach { (k, v) -> put(k, v) } }
            val body = JSONObject().put("comm", comm).put("album", qqSearchModule(module, method, params))
            return JSONObject(PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), cookie,
                mapOf("Referer" to "https://y.qq.com/")).text).qqSearchData("album")
        }
        val info = request("music.musichallAlbum.AlbumInfoServer", "GetAlbumDetail", JSONObject().put("albumMid", mid))
        val basic = info.optJSONObject("basicInfo")
        val tracks = mutableListOf<MusicTrack>()
        var begin = 0
        do {
            val page = request("music.musichallAlbum.AlbumSongList", "GetAlbumSongList",
                JSONObject().put("albumMid", mid).put("begin", begin).put("num", 100).put("order", 2))
            val items = page.optJSONArray("songList")?.searchObjects().orEmpty()
            tracks += items.mapNotNull { item -> runCatching { (item.optJSONObject("songInfo") ?: item).toQqTrack(vip) }.getOrNull() }
            begin += items.size
        } while (items.isNotEmpty() && begin < page.optInt("totalNum"))
        return album.copy(title = basic?.searchText("albumName").orEmpty().ifBlank { album.title },
            description = listOf(basic?.searchText("publishDate"), basic?.searchText("desc"))
                .filterNotNull().filter { it.isNotBlank() }.joinToString("\n").ifBlank { album.description },
            tracks = QqTrackAccessResolver().resolve(tracks.distinctBy { it.id }, cookie), count = tracks.size)
    }
}
