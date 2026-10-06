package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal const val QQ_RECENT_SONGS_PLAYLIST_ID = "qq-recent:songs"
internal const val QQ_RECENT_SONG_WINDOW_SIZE = 12

internal data class QqRecentPlaySnapshot(
    val songs: List<MusicTrack> = emptyList(),
    val songCount: Int = 0,
    val playlists: List<MusicPlaylist> = emptyList(),
)

internal fun qqRecentPlayRequest(): JSONObject = JSONObject()
    .put("req_0", qqRecentPlayModule("GetPlayRecentlyInfo", JSONObject()
        .put("type", 2).put("updateTime", 0).put("requestCnt", 500)))
    .put("req_1", qqRecentPlayModule("GetPlayRecentlyInfo", JSONObject()
        .put("type", 4).put("updateTime", 0).put("requestCnt", 500)))
    .put("req_2", qqRecentPlayModule("GetPlayRecentlyCount", JSONObject().put("updateTime", 0)))

internal fun qqRecentPlayReportRequest(
    track: MusicTrack,
    lastTimeSeconds: Long,
    listenCount: Int = 1,
): JSONObject {
    val songId = track.catalogId.toLongOrNull()?.takeIf { it > 0L }
        ?: throw IllegalArgumentException("最近播放歌曲缺少数字编号")
    val songMid = track.qqPlaybackMid().takeIf(::isValidQqTrackMid)
        ?: throw IllegalArgumentException("最近播放歌曲缺少 MID")
    val item = JSONObject()
        .put("id", songId.toString())
        .put("type", 2)
        .put("lastTime", lastTimeSeconds.coerceAtLeast(0L))
        .put("listenCnt", listenCount.coerceAtLeast(1))
        .put("auxillaryID", songMid)
    return JSONObject().put("req_0", JSONObject()
        .put("module", "music.musicasset.PlayRecentlyWrite")
        .put("method", "ReportPlayRecentlyInfo")
        .put("param", JSONObject().put("data", JSONArray().put(item))))
}

/** 音乐流短卡只有数字 songId；真实 MID 补齐前不能消耗本次播放的上报机会。 */
internal fun MusicTrack.hasQqRecentPlayIdentity(): Boolean =
    catalogId.toLongOrNull()?.let { it > 0L } == true &&
        qqPlaybackMid().let { it.isNotBlank() && isValidQqTrackMid(it) }

internal fun requireQqRecentPlayReportSuccess(response: JSONObject) {
    if (response.optInt("code", -1) != 0) throw PlatformApiException("最近播放同步失败")
    val item = response.optJSONObject("req_0") ?: throw PlatformApiException("最近播放同步返回不完整")
    if (item.optInt("code", -1) != 0) throw PlatformApiException("最近播放同步失败")
    val data = item.optJSONObject("data") ?: throw PlatformApiException("最近播放同步返回不完整")
    if (data.optInt("ret", -1) != 0) throw PlatformApiException("最近播放同步失败")
}

private fun qqRecentPlayModule(method: String, param: JSONObject): JSONObject = JSONObject()
    .put("module", "music.musicasset.PlayRecentlyRead")
    .put("method", method)
    .put("param", param)

internal fun parseQqRecentPlay(response: JSONObject, hasVipAccess: Boolean): QqRecentPlaySnapshot {
    val songData = response.qqRecentData("req_0")
    val folderData = response.qqRecentData("req_1")
    val countData = response.qqRecentData("req_2")
    val songs = songData.optJSONObject("data")?.optJSONArray("songList").qqRecentObjects { item ->
        val track = item.optJSONObject("track")?.toQqTrack(hasVipAccess)
            ?: throw IllegalArgumentException("最近播放歌曲缺少 track")
        item.optLong("lastTime") to track
    }.sortedByDescending { it.first }.map { it.second }.distinctBy(MusicTrack::id)
    val playlists = folderData.optJSONObject("data")?.optJSONArray("geDanList").qqRecentObjects { item ->
        val remoteId = item.optLong("id").takeIf { it > 0L }?.toString()
            ?: throw IllegalArgumentException("最近播放歌单缺少编号")
        val title = item.optString("name").trim().ifBlank { throw IllegalArgumentException("最近播放歌单缺少名称") }
        val colors = qqArtworkColors(title)
        Triple(item.optInt("index", Int.MAX_VALUE), item.optLong("lastTime"), MusicPlaylist(
            id = "qq-$remoteId",
            source = MusicSource.QQ,
            title = title,
            subtitle = "歌单",
            description = "最近播放的 QQ 音乐歌单",
            count = item.optInt("num").coerceAtLeast(0),
            artworkStart = colors.first,
            artworkEnd = colors.second,
            artworkMark = title.take(1),
            tracks = emptyList(),
            artworkUrl = item.optString("pic").ifBlank { item.optString("albumPicUrl") }.qqHttpsUrl(),
        ))
    }.sortedWith(compareBy<Triple<Int, Long, MusicPlaylist>> { it.first }.thenByDescending { it.second })
        .map { it.third }.distinctBy(MusicPlaylist::id)
    val count = countData.optJSONObject("counts")?.length()?.takeIf { it > 0 } ?: songs.size
    return QqRecentPlaySnapshot(songs, count, playlists)
}

private fun JSONObject.qqRecentData(key: String): JSONObject {
    if (optInt("code", -1) != 0) throw PlatformApiException("最近播放加载失败")
    val item = optJSONObject(key) ?: throw PlatformApiException("最近播放返回不完整")
    if (item.optInt("code", -1) != 0) throw PlatformApiException("最近播放加载失败")
    val data = item.optJSONObject("data") ?: throw PlatformApiException("最近播放返回不完整")
    if (data.optInt("code", 0) != 0) throw PlatformApiException("最近播放加载失败")
    return data
}

private fun <T> JSONArray?.qqRecentObjects(transform: (JSONObject) -> T): List<T> = buildList {
    val values = this@qqRecentObjects ?: return@buildList
    for (index in 0 until values.length()) {
        values.optJSONObject(index)?.let { runCatching { transform(it) }.getOrNull() }?.let(::add)
    }
}

internal fun QqRecentPlaySnapshot.asRecentPlaylists(): List<MusicPlaylist> {
    val first = songs.firstOrNull()
    val colors = qqArtworkColors("已播歌曲")
    val songsPlaylist = MusicPlaylist(
        id = QQ_RECENT_SONGS_PLAYLIST_ID,
        source = MusicSource.QQ,
        title = "已播歌曲",
        subtitle = "最近播放",
        description = "已自动同步你在其他设备播放的歌曲",
        count = songCount.coerceAtLeast(songs.size),
        artworkStart = first?.artworkStart ?: colors.first,
        artworkEnd = first?.artworkEnd ?: colors.second,
        artworkMark = "播",
        tracks = songs,
        artworkUrl = first?.artworkUrl,
    )
    return listOf(songsPlaylist) + playlists
}
