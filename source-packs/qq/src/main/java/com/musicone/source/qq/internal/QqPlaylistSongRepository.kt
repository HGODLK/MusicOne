package com.musicone.source.qq.internal

import org.json.JSONObject

/** 构造自建歌单增删歌曲请求：必须同时传 dirId 和 tid，不能拿公开歌单 ID 顶替 dirId。 */
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

    suspend fun addBatch(session: PlatformSession, playlistId: String, tracks: List<MusicTrack>): MusicPlaylist =
        changeBatch(session, playlistId, tracks, true)

    suspend fun changeBatch(session: PlatformSession, playlistId: String, tracks: List<MusicTrack>, add: Boolean): MusicPlaylist {
        require(tracks.isNotEmpty() && tracks.all { it.source == MusicSource.QQ }) { "此操作只接受 QQ 音乐歌曲" }
        val target = targets(session).firstOrNull { it.id == playlistId }
            ?: throw PlatformApiException("只能修改当前账号创建的歌单")
        val resolved = tracks.map { if (it.catalogId.toLongOrNull() != null) it else songs.enrichTrackMetadata(it, session.credential) }
        val body = qqPlaylistSongRequest(session.credential, target, resolved.first(), add)
        body.getJSONObject("req_0").getJSONObject("param").put("v_songInfo",
            qqFavoriteRequestBody(session.credential, qqFavoriteMutation(resolved, add))
                .getJSONObject("req_0").getJSONObject("param").getJSONArray("v_songInfo"))
        val response = PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(), session.credential,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to QQ_ANDROID_USER_AGENT),
            connectTimeoutMs = 8_000, readTimeoutMs = 12_000)
        requireQqFavoriteMutationSuccess(JSONObject(response.text))
        return target
    }

    suspend fun change(session: PlatformSession, playlistId: String, track: MusicTrack, add: Boolean): MusicPlaylist {
        // 写入前重新从当前账号的自建歌单列表确认目标，防止操作到已过期或无权限的歌单
        val target = targets(session).firstOrNull { it.id == playlistId }
            ?: throw PlatformApiException("只能修改当前账号创建的歌单")
        val resolved = songs.enrichTrackMetadata(track, session.credential)
        val response = PlatformHttp.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            qqPlaylistSongRequest(session.credential, target, resolved, add).toString(),
            session.credential,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to QQ_ANDROID_USER_AGENT),
            connectTimeoutMs = 8_000,
            readTimeoutMs = 12_000,
        )
        requireQqFavoriteMutationSuccess(JSONObject(response.text))
        return target
    }
}
