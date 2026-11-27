package com.musicone.source.qq.internal

import org.json.JSONObject

/** 从歌曲的 providerData 里读取 QQ 私有字段（key 名保持兼容旧版持久化数据）。 */
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
