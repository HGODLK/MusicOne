package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.contentText(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.takeIf(String::isNotBlank)
}.orEmpty()

internal fun JSONObject.contentObjects(vararg keys: String): List<JSONObject> {
    val array = keys.firstNotNullOfOrNull { optJSONArray(it) }
        ?: throw PlatformApiException("酷狗内容响应缺少列表")
    return array.contentObjects()
}

internal fun JSONArray.contentObjects(): List<JSONObject> = List(length()) { index ->
    optJSONObject(index) ?: throw PlatformApiException("酷狗内容列表格式错误")
}

internal fun JSONObject.contentRows(vararg names: String): List<JSONObject> = when (val data = opt("data")) {
    is JSONArray -> data.contentObjects()
    is JSONObject -> data.contentObjects(*names)
    else -> throw PlatformApiException("酷狗内容数据格式错误")
}

/** 先由已有解析器保留歌曲身份与音频字段，再补充可导航的实体资料。 */
internal fun JSONObject.toKugouContentTrack(vip: Boolean): MusicTrack {
    val audio = optJSONObject("audio_info")
    val song = JSONObject(toString()).apply {
        if (audio != null) {
            audio.keys().forEach { key -> put(key, audio.get(key)) }
            optJSONObject("album_info")?.let { album ->
                if (!has("album_id") && album.has("album_id")) put("album_id", album.get("album_id"))
                if (!has("album_name") && album.has("album_name")) put("album_name", album.get("album_name"))
            }
        }
        // 歌手单曲使用顶层 audio_name，专辑单曲则放在 base。
        val base = optJSONObject("base")
        // 专辑接口的真实编号也在 base；已有顶层编号及其别名始终优先。
        if (contentText("AlbumID", "album_id", "albumid").isBlank()) {
            base?.contentText("album_id")?.takeIf(String::isNotBlank)?.let { put("album_id", it) }
        }
        if (contentText("MixSongID", "mixsongid", "album_audio_id", "audio_id", "audioid").isBlank()) {
            listOf("album_audio_id", "audio_id").forEach { key ->
                base?.contentText(key)?.takeIf(String::isNotBlank)?.let { put(key, it) }
            }
        }
        if (contentText("SongName", "songname", "song_name").isBlank()) {
            contentText("audio_name").ifBlank { base?.contentText("audio_name").orEmpty() }
                .takeIf(String::isNotBlank)?.let { put("song_name", it) }
        }
        if (contentText("SingerName", "singername", "author_name").isBlank()) {
            base?.contentText("author_name")?.takeIf(String::isNotBlank)?.let { put("author_name", it) }
        }
    }
    return song.enrichKugouContentTrack(song.toKugouTrack(vip))
}

internal fun JSONObject.enrichKugouContentTrack(track: MusicTrack): MusicTrack {
    return track.withKugouTrackReferences(this).copy(
        artworkUrl = track.artworkUrl.normalizeKugouContentArtwork() ?: kugouContentTrackArtwork())
}

internal fun JSONObject.toKugouContentEntity(kind: String): SourceEntity {
    val id = if (kind == "artist") contentText("AuthorId", "AuthorID", "author_id", "singerid", "SingerId", "id")
        else contentText("AlbumID", "album_id", "albumid", "id")
    if (id.isBlank() || id == "0") throw PlatformApiException("酷狗返回了无效${if (kind == "artist") "歌手" else "专辑"}")
    val name = cleanKugouText(if (kind == "artist") contentText("AuthorName", "author_name", "singername", "name")
        else contentText("AlbumName", "album_name", "albumname", "name"))
    val artwork = kugouContentEntityArtwork(kind)
    return SourceEntity(SourceEntityRef(id, name, kind),
        description = cleanKugouText(contentText("intro", "Intro", "description", "author_intro")),
        artwork = SourceArtwork(artwork, mark = name.take(1)),
        attributes = buildMap {
            if (kind == "artist") kugouContentArtistBackgrounds().takeIf { it.isNotEmpty() }
                ?.let { put("backgrounds", JSONArray(it).toString()) }
            contentText("SongCount", "songcount", "song_count", "audio_total").toLongOrNull()?.let { put("trackCount", it.toString()) }
            contentText("album_count", "album_total").toLongOrNull()?.let { put("collectionCount", it.toString()) }
            contentText("publish_date", "PublishDate", "publishtime").takeIf(String::isNotBlank)?.let { put("发行时间", it) }
            contentText("author_name", "AuthorName", "SingerName", "singername").takeIf(String::isNotBlank)?.let { put("歌手", cleanKugouText(it)) }
        })
}

internal fun JSONObject.kugouContentAlbumEntity(id: String): SourceEntity {
    requireKugouContentSuccess(this)
    val rows = contentRows("info", "list")
    val matching = rows.firstOrNull { it.contentText("album_id", "albumid", "AlbumID") == id }
        ?: throw PlatformApiException("酷狗未返回对应专辑详情")
    return matching.toKugouContentEntity("album")
}

internal fun JSONObject.toKugouContentCollection(account: String?, album: Boolean): SourceCollection {
    val id = if (album) contentText("AlbumID", "album_id", "albumid", "id")
        else contentText("SpecialID", "SpecialId", "specialid", "special_id")
            .takeIf { value -> value.isNotBlank() && value.all(Char::isDigit) && value.any { it != '0' } }.orEmpty()
    // MoeKoeMusic 搜索将 gid 作为 global_collection_id 打开；数字全局编号也不能送入旧 special 接口。
    val globalId = if (album) "" else sequenceOf("global_collection_id", "gid")
        .map { contentText(it) }.firstOrNull { it.isNotBlank() && it != "0" }.orEmpty()
    if ((id.isBlank() || id == "0") && globalId.isBlank()) throw PlatformApiException("酷狗返回了无效集合")
    val title = cleanKugouText(if (album) contentText("AlbumName", "album_name", "albumname", "name")
        else contentText("SpecialName", "specialname", "name"))
    val remote = when {
        album -> "album:$id"
        id.isNotBlank() -> id
        else -> "global:$globalId"
    }
    val stableId = "kugou-$remote"
    val payload = JSONObject().put("id", stableId)
    listOf("global_collection_id", "list_create_userid", "list_create_listid").forEach { key ->
        contentText(key).takeIf(String::isNotBlank)?.let { payload.put(key, it) }
    }
    if (globalId.isNotBlank()) payload.put("global_collection_id", globalId)
    if (!album && id.isBlank()) payload.put("collection_route", "global")
    return SourceCollection(TrackIdentity(KUGOU_SOURCE_ID, account, remote), stableId, title,
        subtitle = cleanKugouText(contentText("SingerName", "singername", "author_name", "AuthorName", "UserName", "username", "nickname"))
            .ifBlank { if (album) kugouContentAlbumAuthors() else "" },
        description = cleanKugouText(contentText("intro", "Intro", "description")),
        count = contentText("SongCount", "songcount", "song_count", "audio_total", "count").toIntOrNull(),
        artwork = SourceArtwork(kugouContentEntityArtwork("album"), mark = title.take(1)),
        payload = SourcePayload("kugou.collection", 1, payload.toString()))
}

/** 歌手专辑行展示资料摘要；完整简介只随专辑详情读取，不塞进公共列表行。 */
internal fun JSONObject.toKugouArtistAlbumCollection(account: String?): SourceCollection {
    val collection = toKugouContentCollection(account, true)
    val data = JSONObject(collection.payload.encoded).put("album_list_intro", collection.description)
    val summary = listOfNotNull(
        collection.subtitle.takeIf(String::isNotBlank),
        cleanKugouText(contentText("publish_date", "PublishDate", "publishtime", "publish_time")).takeIf(String::isNotBlank),
        collection.count?.let { "${it}首" },
    ).joinToString(" · ")
    return collection.copy(description = summary, payload = collection.payload.copy(encoded = data.toString()))
}

/** 搜索专辑与歌手专辑使用相同公开集合契约，标题、封面和副标题均可由公共模板直接消费。 */
internal fun JSONObject.toKugouSearchAlbumCollection(account: String?): SourceCollection {
    val id = contentText("albumid", "album_id", "AlbumID")
    if (id.isBlank() || !id.all(Char::isDigit) || id.all { it == '0' }) throw PlatformApiException("酷狗搜索专辑缺少有效专辑编号")
    val album = toKugouArtistAlbumCollection(account)
    return album.copy(subtitle = album.description)
}

private fun JSONObject.kugouContentAlbumAuthors(): String =
    (optJSONArray("singers") ?: optJSONArray("authors"))?.contentObjects().orEmpty()
        .map { cleanKugouText(it.contentText("name", "author_name")) }
        .filter(String::isNotBlank).distinct().joinToString(" / ")

internal fun SourceCollection.kugouAlbumDetailDescription(): String {
    if (!identity.remoteId.startsWith("album:") || payload.format != "kugou.collection") return description
    val data = runCatching { JSONObject(payload.encoded) }.getOrNull() ?: return description
    return if (data.has("album_list_intro") && !data.isNull("album_list_intro")) data.getString("album_list_intro") else description
}

internal fun JSONObject.kugouContentSuggestions(): List<String> {
    val groups = optJSONArray("data") ?: throw PlatformApiException("酷狗搜索建议响应格式错误")
    return groups.contentObjects().flatMap { group ->
        group.contentObjects("RecordDatas", "record_datas").map { item ->
            cleanKugouText(item.contentText("HintInfo", "hint_info", "keyword", "name"))
        }
    }.filter(String::isNotBlank).distinct()
}

private fun cleanKugouText(value: String): String = value
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()
