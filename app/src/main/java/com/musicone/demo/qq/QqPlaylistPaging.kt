package com.musicone.demo

import org.json.JSONObject

internal fun qqPlaylistPageRequest(playlist: MusicPlaylist, offset: Int, cookie: String): JSONObject =
    JSONObject().put("comm", qqPlaybackComm(cookie)).put("playlist", JSONObject()
        .put("module", "music.srfDissInfo.DissInfo").put("method", "CgiGetDiss")
        .put("param", JSONObject()
            .put("disstid", if (playlist.id == QQ_FAVORITES_PLAYLIST_ID) 0L else playlist.remoteId().toLong())
            .put("dirid", if (playlist.id == QQ_FAVORITES_PLAYLIST_ID) 201 else 0)
            .put("song_begin", offset).put("song_num", PLAYLIST_TRACK_PAGE_SIZE)
            .put("tag", true).put("userinfo", true).put("orderlist", true).put("onlysonglist", false)))

internal class QqPlaylistPaging(
    private val request: (JSONObject, String) -> JSONObject = { body, cookie ->
        JSONObject(PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), cookie,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 2005000982(android 14)")).text)
    },
) {
    /** 只取一首以获得目录标题，避免为修复标题加载整页歌曲或查询歌曲权限。 */
    fun title(playlist: MusicPlaylist, cookie: String): String? {
        val body = qqPlaylistPageRequest(playlist, 0, cookie)
        body.getJSONObject("playlist").getJSONObject("param").put("song_num", 1)
        val root = request(body, cookie)
        val response = root.optJSONObject("playlist") ?: return null
        if (root.optInt("code", 0) != 0 || response.optInt("code", -1) != 0) return null
        val data = response.optJSONObject("data") ?: return null
        if (data.optInt("code", 0) != 0 || data.optInt("subcode", 0) != 0) return null
        val info = data.optJSONObject("dirinfo") ?: return null
        return info.optString("title").ifBlank { info.optString("dissname") }.takeIf(String::isNotBlank)
    }

    fun page(playlist: MusicPlaylist, cookie: String, hasVipAccess: Boolean, offset: Int,
        resolveAccess: Boolean = true): PlaylistTrackPage {
        val root = request(qqPlaylistPageRequest(playlist, offset, cookie), cookie)
        val response = root.optJSONObject("playlist") ?: throw PlatformApiException("QQ 音乐没有返回歌单内容")
        if (root.optInt("code", 0) != 0 || response.optInt("code", -1) != 0) {
            throw PlatformApiException("QQ 音乐歌单内容加载失败")
        }
        val data = response.getJSONObject("data")
        val page = data.parseQqPlaylistTrackPage(playlist, hasVipAccess, offset)
        if (!resolveAccess) return page
        return page.copy(playlist = page.playlist.copy(
            tracks = QqTrackAccessResolver().resolve(page.playlist.tracks, cookie)))
    }
}

internal fun JSONObject.parseQqPlaylistTrackPage(
    fallback: MusicPlaylist, hasVipAccess: Boolean, offset: Int,
): PlaylistTrackPage {
    if (optInt("code", 0) != 0 || optInt("subcode", 0) != 0) {
        throw PlatformApiException("QQ 音乐歌单内容加载失败")
    }
    val info = optJSONObject("dirinfo") ?: JSONObject()
    val songs = optJSONArray("songlist")
        ?: throw PlatformApiException("QQ 音乐没有返回歌单歌曲")
    // 复用原详情映射，保留封面、描述解码与歌曲权益规则。
    val legacy = JSONObject().put("dissname", info.optString("title").ifBlank { info.optString("dissname") })
        .put("nickname", info.optJSONObject("creator")?.optString("nick").orEmpty())
        .put("desc", info.optString("desc")).put("songnum", optInt("total_song_num", fallback.count))
        .put("logo", info.optString("picurl").ifBlank { info.optString("cover") }).put("songlist", songs)
    val detail = JSONObject().put("cdlist", org.json.JSONArray().put(legacy)).parseQqPlaylist(fallback, hasVipAccess)
    return PlaylistTrackPage(detail, nextPlaylistTrackCursor(offset, songs.length(), detail.count,
        if (has("hasmore")) optInt("hasmore") != 0 else null))
}
