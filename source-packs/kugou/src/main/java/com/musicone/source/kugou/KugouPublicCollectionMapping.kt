package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.kugouPublicInfo(gid: String): JSONObject {
    val value = opt("data")
    val candidates = when (value) {
        is JSONArray -> value.contentObjects()
        is JSONObject -> value.optJSONArray("info")?.contentObjects() ?: listOf(value)
        else -> emptyList()
    }
    return candidates.firstOrNull { it.contentText("global_collection_id", "list_create_gid") == gid }
        ?: candidates.singleOrNull()?.takeIf { it.contentText("global_collection_id", "list_create_gid").isBlank() &&
            it.contentText("name", "specialname").isNotBlank() }
        ?: throw PlatformApiException("酷狗未返回对应公开歌单资料")
}

internal fun SourceCollection.withKugouPublicInfo(info: JSONObject): SourceCollection {
    val data = runCatching { JSONObject(payload.encoded) }.getOrElse { JSONObject() }
    listOf("global_collection_id", "list_create_userid", "list_create_listid", "list_create_gid").forEach { key ->
        info.contentText(key).takeIf(String::isNotBlank)?.let { data.put(key, it) }
    }
    val cover = info.contentText("pic", "flexible_cover", "imgurl").takeIf(String::isNotBlank)
        ?.replace("{size}", "500")?.let { if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://") }
    return copy(title = info.contentText("name", "specialname").ifBlank { title },
        subtitle = info.contentText("list_create_username", "nickname", "username").ifBlank { subtitle },
        description = info.contentText("intro").ifBlank { description },
        count = info.contentText("count", "songcount").toIntOrNull() ?: count,
        artwork = artwork.copy(url = cover ?: artwork.url), owned = false, accountFavorites = false,
        payload = SourcePayload("kugou.collection", 1, data.toString()))
}

/** 把新版公开歌单里的歌曲字段转成通用格式，再交给原有解析器提取音质和 hash。 */
internal fun JSONObject.kugouPublicCollectionTrack(vip: Boolean): MusicTrack {
    val normalized = JSONObject(toString())
    optJSONObject("albuminfo")?.let { album ->
        if (!normalized.has("album_name")) normalized.put("album_name", album.contentText("name"))
        if (!normalized.has("album_id")) normalized.put("album_id", album.contentText("id"))
    }
    optJSONArray("singerinfo")?.let { singers ->
        normalized.put("singerinfos", singers)
        if (!normalized.has("singername")) normalized.put("singername", singers.contentObjects()
            .map { it.contentText("name") }.filter(String::isNotBlank).joinToString(" / "))
    }
    // 下载收费不等于播放收费，播放权限只看歌曲信息和标准档权限
    return normalized.toKugouContentTrack(vip)
}

/** 公开歌单里被屏蔽的歌只有 fileid/shield 字段，转成不可播放占位项保留下来，不中断整页解析。 */
internal fun JSONObject.kugouPublicCollectionItem(gid: String, account: String?, vip: Boolean): SourceTrackDetails {
    if (optInt("shield") != 1) return KugouSourceMapping.track(kugouPublicCollectionTrack(vip), account)
    val fileId = contentText("fileid").toLongOrNull()?.takeIf { it > 0 }
        ?: throw PlatformApiException("酷狗屏蔽歌曲缺少歌单项编号")
    // 屏蔽曲目只用 fileid 做占位 ID，不填音质 hash 和播放参数
    val remote = "unavailable:$gid:$fileId"
    return SourceTrackDetails(
        track = SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, account, remote), "暂不可用的歌曲"),
        stableId = "kugou-$remote",
        access = TrackAccessInfo(playable = false, reason = "酷狗已屏蔽该歌曲资料"),
    )
}
