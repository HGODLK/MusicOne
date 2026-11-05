package com.musicone.source.subsonic.internal

import org.json.JSONArray
import org.json.JSONObject

internal data class SubsonicNamedRef(val id: String, val name: String)

internal data class SubsonicSong(
    val id: String,
    val title: String,
    val album: String,
    val albumId: String,
    val artist: String,
    val artists: List<SubsonicNamedRef>,
    val durationSeconds: Int,
    val track: Int,
    val discNumber: Int,
    val year: Int,
    val suffix: String,
    val contentType: String,
    val bitRate: Int,
    val coverArt: String,
    val starred: Boolean,
    /** 保留原始条目，便于每日推荐等缓存原样复原。 */
    val raw: JSONObject,
) {
    val durationMs: Long get() = durationSeconds.coerceAtLeast(0) * 1000L
}

internal data class SubsonicAlbum(
    val id: String,
    val name: String,
    val artist: String,
    val artistId: String,
    val songCount: Int?,
    val coverArt: String,
    val year: Int,
    val starred: Boolean,
)

internal data class SubsonicArtist(
    val id: String,
    val name: String,
    val albumCount: Int?,
    val coverArt: String,
    val artistImageUrl: String,
)

internal data class SubsonicPlaylist(
    val id: String,
    val name: String,
    val comment: String,
    val owner: String,
    val public: Boolean,
    val songCount: Int?,
    val coverArt: String,
    val readonly: Boolean,
)

/** 旧版服务器在只有一项时可能返回对象而不是数组，两种写法都接受。 */
internal fun JSONObject.objects(name: String): List<JSONObject> = when (val value = opt(name)) {
    is JSONArray -> (0 until value.length()).mapNotNull(value::optJSONObject)
    is JSONObject -> listOf(value)
    else -> emptyList()
}

private fun JSONObject.text(name: String): String = if (isNull(name)) "" else optString(name).trim()
private fun JSONObject.count(name: String): Int? = if (has(name) && !isNull(name)) optInt(name, -1).takeIf { it >= 0 } else null

internal fun JSONObject.song(): SubsonicSong? {
    val id = text("id").ifBlank { return null }
    val artists = objects("artists").mapNotNull { item ->
        val artistId = item.text("id"); val name = item.text("name")
        if (name.isBlank()) null else SubsonicNamedRef(artistId, name)
    }.ifEmpty {
        val name = text("artist")
        if (name.isBlank()) emptyList() else listOf(SubsonicNamedRef(text("artistId"), name))
    }
    return SubsonicSong(
        id = id,
        title = text("title").ifBlank { text("path").substringAfterLast('/').substringBeforeLast('.').ifBlank { id } },
        album = text("album"), albumId = text("albumId"),
        artist = text("displayArtist").ifBlank { text("artist") }.ifBlank { artists.joinToString(" / ") { it.name } },
        artists = artists,
        durationSeconds = optInt("duration", 0), track = optInt("track", 0), discNumber = optInt("discNumber", 0),
        year = optInt("year", 0), suffix = text("suffix").lowercase(), contentType = text("contentType"),
        bitRate = optInt("bitRate", 0), coverArt = text("coverArt"), starred = has("starred") && !isNull("starred"),
        raw = this,
    )
}

internal fun JSONObject.album(): SubsonicAlbum? {
    val id = text("id").ifBlank { return null }
    return SubsonicAlbum(id, text("name").ifBlank { text("title") }.ifBlank { text("album") },
        text("displayArtist").ifBlank { text("artist") }, text("artistId"), count("songCount"), text("coverArt"),
        optInt("year", 0), has("starred") && !isNull("starred"))
}

internal fun JSONObject.artist(): SubsonicArtist? {
    val id = text("id").ifBlank { return null }
    return SubsonicArtist(id, text("name"), count("albumCount"), text("coverArt"), text("artistImageUrl"))
}

internal fun JSONObject.playlist(): SubsonicPlaylist? {
    val id = text("id").ifBlank { return null }
    return SubsonicPlaylist(id, text("name"), text("comment"), text("owner"), optBoolean("public"),
        count("songCount"), text("coverArt"), optBoolean("readonly"))
}

/** 专辑内按碟号、曲序排列；缺少曲序的保持服务器顺序。 */
internal fun List<SubsonicSong>.inAlbumOrder(): List<SubsonicSong> =
    withIndex().sortedWith(compareBy({ it.value.discNumber }, { if (it.value.track > 0) it.value.track else Int.MAX_VALUE }, { it.index }))
        .map { it.value }
