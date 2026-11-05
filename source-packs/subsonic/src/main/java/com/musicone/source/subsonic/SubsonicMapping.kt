package com.musicone.source.subsonic

import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.*
import org.json.JSONObject

internal const val SUBSONIC_SOURCE_ID = "com.musicone.source.subsonic"
private const val TRACK_FORMAT = "subsonic.track"
private const val COLLECTION_FORMAT = "subsonic.collection"
private const val REF_FORMAT = "subsonic.ref"

internal enum class SubsonicCollectionKind { ALBUM, PLAYLIST, STARRED }

/** 从包内载荷还原的集合定位；server 为空表示旧数据或宿主转换后缺失，按当前服务器处理。 */
internal data class SubsonicCollectionRef(val serverKey: String?, val kind: SubsonicCollectionKind, val id: String)

/** 从包内载荷还原的歌曲定位。 */
internal data class SubsonicTrackRef(val serverKey: String?, val id: String, val suffix: String,
    val contentType: String, val bitRate: Int)

internal fun trackStableId(serverKey: String, songId: String) = "subsonic-$serverKey-$songId"
private fun collectionStableId(serverKey: String, kind: SubsonicCollectionKind, id: String) = when (kind) {
    SubsonicCollectionKind.STARRED -> "subsonic-$serverKey-starred"
    SubsonicCollectionKind.ALBUM -> "subsonic-$serverKey-album-$id"
    SubsonicCollectionKind.PLAYLIST -> "subsonic-$serverKey-playlist-$id"
}

/** 封面统一用 600px；token 与 salt 固定在账号里，同一封面的地址保持不变，便于图片缓存。 */
internal class SubsonicArtworkUrls(private val client: SubsonicClient) {
    fun cover(server: SubsonicServer, coverArt: String): String? =
        coverArt.takeIf(String::isNotBlank)?.let { client.url(server, "getCoverArt", listOf("id" to it, "size" to "600")) }
}

/** 封面带主题色渐变和首字兜底，没有封面或加载失败时仍有可辨认的占位。 */
internal fun subsonicArtwork(url: String?, mark: String) = SourceArtwork(url, 0xFFF5B03C, 0xFFD9780A, mark)

private fun refPayload(server: SubsonicServer) = SourcePayload(REF_FORMAT, 1, JSONObject().put("server", server.key).toString())
internal fun SourceEntityRef.serverKey(): String? =
    if (payload.format == REF_FORMAT && payload.encoded.isNotBlank()) JSONObject(payload.encoded).optString("server").ifBlank { null } else null

internal fun SubsonicSong.details(server: SubsonicServer, urls: SubsonicArtworkUrls): SourceTrackDetails {
    val artwork = urls.cover(server, coverArt)
    val artistRefs = artists.filter { it.id.isNotBlank() }.map { SourceEntityRef(it.id, it.name, "artist", refPayload(server)) }
    val payload = JSONObject().put("server", server.key).put("id", id).put("albumId", albumId)
        .put("suffix", suffix).put("contentType", contentType).put("bitRate", bitRate)
    return SourceTrackDetails(
        track = SourceTrack(TrackIdentity(SUBSONIC_SOURCE_ID, server.key, id), title,
            artists.map { it.name }.ifEmpty { listOfNotNull(artist.takeIf(String::isNotBlank)) }, artwork),
        stableId = trackStableId(server.key, id),
        album = album, durationMs = durationMs,
        artwork = subsonicArtwork(artwork, title.take(1)),
        artists = artistRefs,
        albumRef = albumId.takeIf(String::isNotBlank)?.let { SourceEntityRef(it, album, "album", refPayload(server)) },
        qualities = subsonicTrackQualities(suffix, bitRate),
        payload = SourcePayload(TRACK_FORMAT, 1, payload.toString()),
        artistsText = artist,
    )
}

internal fun SourceTrackDetails.subsonicRef(): SubsonicTrackRef {
    require(track.identity.sourceId == SUBSONIC_SOURCE_ID) { "曲目所属音源不匹配" }
    val value = if (payload.format == TRACK_FORMAT && payload.encoded.isNotBlank()) JSONObject(payload.encoded) else null
    val server = value?.optString("server")?.ifBlank { null } ?: track.identity.accountId
    val id = value?.optString("id")?.ifBlank { null } ?: track.identity.remoteId
    return SubsonicTrackRef(server, id, value?.optString("suffix").orEmpty(), value?.optString("contentType").orEmpty(),
        value?.optInt("bitRate", 0) ?: 0)
}

internal fun SubsonicAlbum.collection(server: SubsonicServer, urls: SubsonicArtworkUrls, subtitle: String = artist): SourceCollection =
    SourceCollection(TrackIdentity(SUBSONIC_SOURCE_ID, server.key, "album:$id"),
        collectionStableId(server.key, SubsonicCollectionKind.ALBUM, id), name, subtitle, count = songCount,
        artwork = subsonicArtwork(urls.cover(server, coverArt), name.take(1)),
        payload = collectionPayload(server, SubsonicCollectionKind.ALBUM, id))

internal fun SubsonicPlaylist.collection(server: SubsonicServer, urls: SubsonicArtworkUrls): SourceCollection {
    val mine = owner.isBlank() || owner == server.username
    return SourceCollection(TrackIdentity(SUBSONIC_SOURCE_ID, server.key, "playlist:$id"),
        collectionStableId(server.key, SubsonicCollectionKind.PLAYLIST, id), name,
        if (mine) "" else "来自 $owner", comment, songCount,
        subsonicArtwork(urls.cover(server, coverArt), name.take(1)), owned = mine && !readonly,
        payload = collectionPayload(server, SubsonicCollectionKind.PLAYLIST, id))
}

internal fun starredCollection(server: SubsonicServer, count: Int?, artworkUrl: String?): SourceCollection =
    SourceCollection(TrackIdentity(SUBSONIC_SOURCE_ID, server.key, "starred"),
        collectionStableId(server.key, SubsonicCollectionKind.STARRED, ""), "我喜欢的音乐", count = count,
        artwork = subsonicArtwork(artworkUrl, "♥"), accountFavorites = true,
        payload = collectionPayload(server, SubsonicCollectionKind.STARRED, ""))

private fun collectionPayload(server: SubsonicServer, kind: SubsonicCollectionKind, id: String) =
    SourcePayload(COLLECTION_FORMAT, 1, JSONObject().put("server", server.key).put("kind", kind.name).put("id", id).toString())

internal fun SourceCollection.subsonicRef(): SubsonicCollectionRef {
    require(identity.sourceId == SUBSONIC_SOURCE_ID) { "歌单所属音源不匹配" }
    if (payload.format == COLLECTION_FORMAT && payload.encoded.isNotBlank()) {
        val value = JSONObject(payload.encoded)
        val kind = runCatching { SubsonicCollectionKind.valueOf(value.optString("kind")) }.getOrNull()
        if (kind != null) return SubsonicCollectionRef(value.optString("server").ifBlank { identity.accountId }, kind, value.optString("id"))
    }
    // 载荷缺失时退回远端身份，格式与上面写入时一致。
    val remote = identity.remoteId
    return when {
        remote == "starred" -> SubsonicCollectionRef(identity.accountId, SubsonicCollectionKind.STARRED, "")
        remote.startsWith("album:") -> SubsonicCollectionRef(identity.accountId, SubsonicCollectionKind.ALBUM, remote.removePrefix("album:"))
        remote.startsWith("playlist:") -> SubsonicCollectionRef(identity.accountId, SubsonicCollectionKind.PLAYLIST, remote.removePrefix("playlist:"))
        else -> throw SourceOperationException("SOURCE_REQUEST_FAILED", "无法识别该歌单")
    }
}

internal fun SubsonicArtist.entity(server: SubsonicServer, urls: SubsonicArtworkUrls, description: String = "",
    imageUrl: String? = null, trackCount: Int? = null): SourceEntity {
    val artwork = imageUrl ?: urls.cover(server, coverArt) ?: artistImageUrl.takeIf(String::isNotBlank)
    return SourceEntity(SourceEntityRef(id, name, "artist", refPayload(server)), description, subsonicArtwork(artwork, name.take(1)),
        buildMap {
            albumCount?.let { put("collectionCount", it.toString()) }
            trackCount?.let { put("trackCount", it.toString()) }
            imageUrl?.let { put("backgrounds", org.json.JSONArray(listOf(it)).toString()) }
        })
}

internal fun SubsonicServer.account() = SourceAccount(key, username, signature = displayHost,
    membership = null)
