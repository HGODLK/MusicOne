package com.musicone.source.qq.internal

import org.json.JSONObject

/** QQ字段由包内解读，公共曲目只保存不透明数据。键名延续旧持久化身份。 */
internal val MusicTrack.qqSongMid: String get() = providerData["songMid"].orEmpty()

internal val MusicTrack.qqAccess: QqTrackAccessInfo?
    get() = providerData["qqAccess"]?.let { runCatching { JSONObject(it).toQqTrackAccessInfo() }.getOrNull() }

internal fun MusicTrack.withQqSongMid(value: String): MusicTrack =
    copy(providerData = providerData + ("songMid" to value))

internal fun MusicTrack.withQqAccessInfo(value: QqTrackAccessInfo?): MusicTrack = copy(
    providerData = providerData.toMutableMap().apply {
        if (value == null) remove("qqAccess") else put("qqAccess", value.toJson().toString())
    },
)

internal val MusicPlaylist.qqDirectoryId: Long? get() = providerData["qqDirectoryId"]?.toLongOrNull()

internal fun MusicPlaylist.withQqDirectoryId(value: Long?): MusicPlaylist = copy(
    providerData = providerData.toMutableMap().apply {
        if (value == null) remove("qqDirectoryId") else put("qqDirectoryId", value.toString())
    },
)
