package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal data class QqFavoriteSong(
    val songId: Long,
    val songType: Int,
)

internal data class QqFavoriteMutation(
    val songs: List<QqFavoriteSong>,
    val liked: Boolean,
)

internal fun qqFavoriteMutation(track: MusicTrack, liked: Boolean): QqFavoriteMutation {
    return qqFavoriteMutation(listOf(track), liked)
}

internal fun qqFavoriteMutation(tracks: List<MusicTrack>, liked: Boolean): QqFavoriteMutation {
    if (tracks.isEmpty()) throw PlatformApiException("没有可修改的 QQ 音乐歌曲")
    val songs = tracks.distinctBy(MusicTrack::id).map { track ->
        if (track.source != MusicSource.QQ) throw PlatformApiException("仅支持修改 QQ 音乐收藏")
        val songId = track.catalogId.toLongOrNull()
            ?: throw PlatformApiException("QQ 音乐歌曲缺少收藏所需编号")
        QqFavoriteSong(songId, track.providerType)
    }
    return QqFavoriteMutation(songs, liked)
}

internal fun qqFavoriteRequestBody(cookie: String, mutation: QqFavoriteMutation): JSONObject {
    val comm = qqPlaybackComm(cookie)
    if (comm.optString("qq").isBlank() || comm.optString("authst").isBlank()) {
        throw PlatformApiException("QQ 音乐登录状态已失效", 301)
    }
    val request = JSONObject()
        .put("module", "music.musicasset.PlaylistDetailWrite")
        .put("method", if (mutation.liked) "AddSonglist" else "DelSonglist")
        .put("param", JSONObject()
            .put("dirId", 201)
            .put("tid", 0)
            .put("bFmtUtf8", true)
            .put("source", QQ_FAVORITE_WRITE_SOURCE)
            .put("v_songInfo", JSONArray().apply {
                mutation.songs.forEach { song ->
                    put(JSONObject()
                        .put("songId", song.songId)
                        .put("songType", song.songType))
                }
            }))
    return JSONObject().put("comm", comm).put(QQ_FAVORITE_REQUEST_KEY, request)
}

internal fun requireQqFavoriteMutationSuccess(response: JSONObject) {
    val result = response.optJSONObject(QQ_FAVORITE_REQUEST_KEY)
        ?: throw PlatformApiException("QQ 音乐我喜欢操作返回异常")
    val code = result.requiredInt("code")
        ?: throw PlatformApiException("QQ 音乐我喜欢操作返回异常")
    if (code in QQ_LOGIN_ERROR_CODES) throw PlatformApiException("QQ 音乐登录状态已失效", 301)
    if (code != 0) throw PlatformApiException("QQ 音乐我喜欢操作失败（$code）", code)
    val data = result.optJSONObject("data")
        ?: throw PlatformApiException("QQ 音乐我喜欢操作返回异常")
    val retCode = data.requiredInt("retCode")
        ?: throw PlatformApiException("QQ 音乐我喜欢操作返回异常")
    if (retCode != 0) throw PlatformApiException("QQ 音乐我喜欢操作失败（$retCode）", retCode)
}

internal fun qqFavoriteMutationSucceeded(code: Int?, retCode: Int?): Boolean = code == 0 && retCode == 0

private fun JSONObject.requiredInt(name: String): Int? =
    takeIf { has(name) && !isNull(name) }?.optInt(name)

private const val QQ_FAVORITE_REQUEST_KEY = "req_0"
internal const val QQ_FAVORITE_WRITE_SOURCE = "normal"
private val QQ_LOGIN_ERROR_CODES = setOf(1000, 104400, 104401)
