package com.musicone.demo

import org.json.JSONObject

internal fun loadKugouPlaylistTrackPage(
    playlist: MusicPlaylist, cookie: String, vip: Boolean, cursor: PlaylistTrackCursor?,
): PlaylistTrackPage {
    val offset = cursor?.offset ?: 0
    val album = playlist.remoteId().startsWith("album:")
    val endpoint = if (album) "album" else "special"
    val key = if (album) "albumid" else "specialid"
    val id = playlist.remoteId().removePrefix("album:")
    val url = "https://gateway.kugou.com/api/v3/$endpoint/song?$key=${id.urlEncoded()}" +
        "&page=${offset / PLAYLIST_TRACK_PAGE_SIZE + 1}&pagesize=$PLAYLIST_TRACK_PAGE_SIZE&version=9108&area_code=1"
    val response = JSONObject(PlatformHttp.get(url, cookie,
        kugouMobileHeaders() + ("x-router" to "mobilecdn.kugou.com")).text)
    val data = response.optJSONObject("data") ?: throw PlatformApiException("酷狗音乐没有返回歌单内容")
    val raw = data.optJSONArray("info") ?: throw PlatformApiException("酷狗音乐没有返回歌单歌曲")
    val tracks = response.parseKugouPlaylistTracks(vip)
    val total = data.optInt("total", data.optInt("total_count", playlist.count))
    val next = nextPlaylistTrackCursor(offset, raw.length(), total)
    return PlaylistTrackPage(playlist.copy(tracks = tracks,
        count = if (total > 0) total else if (next == null) offset + raw.length() else 0), next)
}
