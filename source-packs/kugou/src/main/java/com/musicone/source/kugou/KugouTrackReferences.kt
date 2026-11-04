package com.musicone.source.kugou

import com.musicone.source.sdk.SourceEntityRef
import org.json.JSONObject

/** 只补公共资料菜单需要的真实实体引用，不改变歌曲身份、音频资源和权益。 */
internal fun MusicTrack.withKugouTrackReferences(raw: JSONObject): MusicTrack {
    val authors = listOf("authors", "Authors", "Singers", "singers", "singerinfos", "singerinfo")
        .flatMap { key -> raw.optJSONArray(key)?.contentObjects().orEmpty() }
        .mapNotNull { author ->
            val base = author.optJSONObject("base") ?: author
            val id = base.referenceText("author_id", "AuthorId", "AuthorID", "SingerId", "SingerID", "singerid", "id")
                .ifBlank { author.referenceText("author_id", "AuthorId", "AuthorID", "SingerId", "SingerID", "singerid", "id") }
            val name = base.referenceText("author_name", "AuthorName", "SingerName", "singername", "name").referenceName()
            if (id.validKugouReferenceId() && name.isNotBlank()) SourceEntityRef(id, name, "artist") else null
        }.ifEmpty {
            val id = raw.referenceText("SingerId", "SingerID", "singerid", "author_id")
            if (id.validKugouReferenceId() && artists.isNotBlank()) listOf(SourceEntityRef(id, artists, "artist")) else emptyList()
        }.distinctBy { it.id }
    val albumInfo = raw.optJSONObject("album_info") ?: raw.optJSONObject("albuminfo")
    val albumId = raw.referenceText("AlbumID", "album_id", "albumid")
        .ifBlank { albumInfo?.referenceText("album_id", "albumid", "id").orEmpty() }
        .ifBlank { mediaId }
    val albumName = raw.referenceText("AlbumName", "album_name", "albumname")
        .ifBlank { albumInfo?.referenceText("album_name", "albumname", "name").orEmpty() }
        .ifBlank { album }.referenceName()
    return copy(
        artistReferences = artistReferences.ifEmpty { authors },
        albumReference = albumReference ?: albumId.takeIf(String::validKugouReferenceId)
            ?.let { SourceEntityRef(it, albumName, "album") },
    )
}

private fun JSONObject.referenceText(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
}.orEmpty()

private fun String.validKugouReferenceId() = isNotBlank() && all(Char::isDigit) && any { it != '0' }
private fun String.referenceName() = replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()
