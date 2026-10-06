package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal fun setQqPlaylistCollected(cookie: String, playlist: MusicPlaylist, collected: Boolean) {
    val uin = qqPersonalizedAccountId(cookie)
    val key = qqCredentialMusicKey(cookie)
    if (uin.isBlank() || key.isBlank()) throw PlatformApiException("请先登录 QQ 音乐", 301)
    val id = playlist.remoteId().toLongOrNull()
        ?: throw PlatformApiException("这个歌单不支持收藏")
    val response = PlatformHttp.postJson(
        QQ_MUSIC_U_API,
        qqPlaylistCollectionRequest(cookie, id, collected).toString(),
        cookie,
        mapOf(
            "Referer" to "https://y.qq.com/",
            "Origin" to "https://y.qq.com",
            "User-Agent" to "QQMusic 2005000982(android 14)",
        ),
    )
    requireQqPlaylistCollectionSuccess(JSONObject(response.text), id)
}

internal data class QqPlaylistCollectionCall(val module: String, val method: String, val playlistIds: List<Long>)

internal fun qqPlaylistCollectionCall(playlistId: Long, collected: Boolean) = QqPlaylistCollectionCall(
    module = "music.musicasset.PlaylistFavWrite",
    method = if (collected) "FavPlaylist" else "CancelFavPlaylist",
    playlistIds = listOf(playlistId),
)

internal fun qqPlaylistCollectionRequest(cookie: String, playlistId: Long, collected: Boolean): JSONObject {
    val call = qqPlaylistCollectionCall(playlistId, collected)
    return JSONObject()
        .put("comm", qqPlaybackComm(cookie))
        .put("req_0", JSONObject()
            .put("module", call.module)
            .put("method", call.method)
            .put("param", JSONObject().put("v_playlistId", JSONArray(call.playlistIds))))
}

internal fun requireQqPlaylistCollectionSuccess(response: JSONObject, playlistId: Long) {
    val topCode = response.optInt("code", -1)
    val request = response.optJSONObject("req_0")
    val requestCode = request?.optInt("code", -1) ?: -1
    val data = request?.optJSONObject("data")
    val result = data?.optInt("result", -1) ?: -1
    val failed = data?.optJSONArray("v_failedPlaylistId")
    val explicitlyFailed = (0 until (failed?.length() ?: 0)).any { failed?.optLong(it) == playlistId }
    if (!qqPlaylistCollectionSucceeded(topCode, requestCode, result, explicitlyFailed)) {
        val reason = data?.optString("reason").orEmpty()
            .ifBlank { request?.optString("message").orEmpty() }
            .ifBlank { "歌单收藏同步失败，请稍后重试" }
        throw PlatformApiException(reason, listOf(result, requestCode, topCode).firstOrNull { it != 0 } ?: -1)
    }
}

internal fun qqPlaylistCollectionSucceeded(
    topCode: Int,
    requestCode: Int,
    result: Int,
    explicitlyFailed: Boolean,
): Boolean = topCode == 0 && requestCode == 0 && result == 0 && !explicitlyFailed

internal fun MusicPlaylist.isOwnedQqPlaylist(createdPlaylists: List<MusicPlaylist>): Boolean =
    source == MusicSource.QQ && (
        isQqFavoritesShortcut() ||
            id.startsWith(QQ_PROFILE_DIRECTORY_ID_PREFIX) ||
            id.startsWith("qq-daily-") ||
            createdPlaylists.any { it.id == id }
        )

private const val QQ_MUSIC_U_API = "https://u.y.qq.com/cgi-bin/musicu.fcg"
