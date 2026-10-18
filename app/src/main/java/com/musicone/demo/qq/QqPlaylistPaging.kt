package com.musicone.demo

import org.json.JSONObject

internal fun qqPlaylistPageRequest(playlist: MusicPlaylist, offset: Int, cookie: String): JSONObject =
    JSONObject().put("comm", qqPlaybackComm(cookie)).put("playlist", JSONObject()
        .put("module", "music.srfDissInfo.DissInfo").put("method", "CgiGetDiss")
        .put("param", JSONObject()
            .put("disstid", playlist.remoteId().toLong())
            .put("dirid", 0).put("song_begin", offset).put("song_num", PLAYLIST_TRACK_PAGE_SIZE)
            .put("tag", true).put("userinfo", true).put("orderlist", true).put("onlysonglist", false)))

internal class QqPlaylistPaging {
    fun page(playlist: MusicPlaylist, cookie: String, hasVipAccess: Boolean, offset: Int,
        resolveAccess: Boolean = true): PlaylistTrackPage {
        val raw = PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg",
            qqPlaylistPageRequest(playlist, offset, cookie).toString(), cookie,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 2005000982(android 14)"))
        val root = JSONObject(raw.text)
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
