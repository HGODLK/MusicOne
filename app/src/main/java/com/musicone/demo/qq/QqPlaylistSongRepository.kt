package com.musicone.demo

import org.json.JSONObject

/** 对照官方目录写接口，同时携带 dirId 与 tid，不能把公开歌单编号当目录编号。 */
internal fun qqPlaylistSongRequest(cookie: String, playlist: MusicPlaylist, track: MusicTrack, add: Boolean): JSONObject {
    val directory = playlist.qqDirectoryId
        ?: playlist.id.removePrefix(QQ_PROFILE_DIRECTORY_ID_PREFIX).toLongOrNull()
        ?: throw PlatformApiException("歌单目录信息不完整，请刷新后重试")
    require(directory > 0) { "歌单目录编号无效" }
    val body = qqFavoriteRequestBody(cookie, qqFavoriteMutation(track, add))
    body.getJSONObject("req_0").getJSONObject("param")
        .put("dirId", directory)
        .put("dirName", playlist.title)
        .put("tid", playlist.remoteId().toLongOrNull() ?: 0L)
    return body
}

internal class QqPlaylistSongRepository {
    private val library = QqLibraryClient()
    private val songs = QqApiClient()

    fun targets(session: PlatformSession): List<MusicPlaylist> {
        val account = session.account ?: throw PlatformApiException("请先登录 QQ 音乐", 301)
        return library.createdPlaylists(session.credential, account.userId)
            .filter { !it.isQqFavoritesShortcut() && it.qqDirectoryId != null }
    }

    suspend fun change(session: PlatformSession, playlistId: String, track: MusicTrack, add: Boolean): MusicPlaylist {
        // 写入前重新从当前账号取得目标，不能依靠页面标题或已过期的归属状态授权。
        val target = targets(session).firstOrNull { it.id == playlistId }
            ?: throw PlatformApiException("只能修改当前账号创建的歌单")
        val resolved = songs.enrichTrackMetadata(track, session.credential)
        val response = PlatformHttp.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            qqPlaylistSongRequest(session.credential, target, resolved, add).toString(),
            session.credential,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 2005000982(android 14)"),
            connectTimeoutMs = 8_000,
            readTimeoutMs = 12_000,
        )
        requireQqFavoriteMutationSuccess(JSONObject(response.text))
        return target
    }
}
