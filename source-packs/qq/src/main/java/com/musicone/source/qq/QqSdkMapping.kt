package com.musicone.source.qq

import com.musicone.source.sdk.*
import com.musicone.source.qq.internal.*
import org.json.JSONArray
import org.json.JSONObject

internal const val QQ_SOURCE_ID = "com.musicone.source.qq"
private val QQ_HOST_MAINTAINED_KEYS = setOf("sourcePayloadFormat", "sourcePayloadVersion", "sourcePayload",
    "musicone.host.originalStableId")

/** SDK只接触完整公共资料和包自有payload，不读取宿主曲目JSON。 */
internal fun MusicTrack.details(accountId: String?): SourceTrackDetails {
    val opaque = providerData + mapOf("catalogId" to catalogId, "mediaId" to mediaId,
        "providerType" to providerType.toString(), "qualityIds" to JSONObject(qualityIds.mapKeys { it.key.name }).toString())
    return SourceTrackDetails(
        track = SourceTrack(TrackIdentity(QQ_SOURCE_ID, accountId, remoteId()), title,
            artists.split('、').filter(String::isNotBlank), artworkUrl, opaque),
        stableId = id, album = album, durationMs = durationMs, artwork = SourceArtwork(artworkUrl, artworkStart, artworkEnd, artworkMark),
        artists = artistRefs.map { SourceEntityRef(it.id, it.name, "artist") },
        albumRef = albumId.takeIf(String::isNotBlank)?.let { SourceEntityRef(it, album, "album") },
        access = TrackAccessInfo(when (accessBadge) { MusicAccessBadge.VIP -> TrackAccessBadge.MEMBERSHIP; MusicAccessBadge.PAID -> TrackAccessBadge.PURCHASE; null -> null },
            trialAvailable, playable, unavailableReason, displayBadge = if (accessBadge == MusicAccessBadge.VIP) SourceTrackBadge("VIP") else null),
        qualities = qualityIds.keys.map { it.sdkQuality() }, lyrics = lyrics.takeIf(List<TimedLyric>::isNotEmpty)?.sdkLyrics(),
        localUri = localUri, payload = SourcePayload("qq.track", 1, JSONObject(opaque).toString()), artistsText = artists, previewUrl = previewUrl,
        identityAliases = qqFavoriteIdentityIds().toList(),
    )
}

internal fun SourceTrackDetails.musicTrack(): MusicTrack {
    require(track.identity.sourceId == QQ_SOURCE_ID) { "曲目所属音源不匹配" }
    // 宿主维护的载荷与身份键不并入自有资料，避免编码进 qq.track 后逐次嵌套膨胀。
    val data = (if (payload.format == "qq.track" && payload.encoded.isNotBlank()) jsonStrings(JSONObject(payload.encoded)) else emptyMap()) +
        track.opaqueData - QQ_HOST_MAINTAINED_KEYS
    val qualityValues = data["qualityIds"]?.let { runCatching { JSONObject(it) }.getOrNull() }
    val ids = buildMap { AudioQuality.entries.forEach { quality -> qualityValues?.optString(quality.name)?.takeIf(String::isNotBlank)?.let { put(quality, it) } } }
    return MusicTrack(stableId.ifBlank { "qq-${track.identity.remoteId}" }, MusicSource.QQ, track.title, artistsText,
        album, durationMs, artwork.startArgb, artwork.endArgb, artwork.mark, previewUrl,
        artwork.url ?: track.artworkUrl, data["catalogId"].orEmpty(), data["mediaId"].orEmpty(), data["providerType"]?.toIntOrNull() ?: 0,
        ids, when(access.badge) { TrackAccessBadge.MEMBERSHIP -> MusicAccessBadge.VIP; TrackAccessBadge.PURCHASE -> MusicAccessBadge.PAID; null -> null },
        access.trialAvailable, access.playable, access.reason, lyrics?.lines.orEmpty().map { line -> TimedLyric(line.startMs, line.text, line.translation,
            line.words.map { LyricWord(it.startMs, it.startMs + it.durationMs, it.text) }, line.parserVersion, line.transliteration) },
        artists.map { MusicArtist(it.id, it.name) }, albumRef?.id.orEmpty(), localUri,
        data - setOf("catalogId", "mediaId", "providerType", "qualityIds"))
}

internal fun MusicPlaylist.collection(accountId: String?): SourceCollection = SourceCollection(
    TrackIdentity(QQ_SOURCE_ID, accountId, remoteId()), id, title, subtitle, description, count,
    SourceArtwork(artworkUrl, artworkStart, artworkEnd, artworkMark), isOwned, isPlatformFavorites || isQqFavoritesShortcut(),
    SourcePayload("qq.collection", 1, JSONObject().put("providerData", JSONObject(providerData)).toString()),
)

internal fun SourceCollection.playlist(): MusicPlaylist {
    require(identity.sourceId == QQ_SOURCE_ID) { "歌单所属音源不匹配" }
    val data = when {
        payload.format == "qq.collection" && payload.encoded.isNotBlank() -> JSONObject(payload.encoded).optJSONObject("providerData")?.let(::jsonStrings).orEmpty()
        payload.format == "musicone.provider-data" && payload.encoded.isNotBlank() -> jsonStrings(JSONObject(payload.encoded))
        else -> emptyMap()
    }
    return MusicPlaylist(stableId.ifBlank { "qq-${identity.remoteId}" }, MusicSource.QQ, title, subtitle, description, count ?: 0,
        artwork.startArgb, artwork.endArgb, artwork.mark, emptyList(), artwork.url, owned, accountFavorites, data)
}

internal fun MusicAccount.sdkAccount() = SourceAccount(userId, nickname, avatarUrl, if (hasVipAccess) "vip" else null,
    backgroundUrl, signature, follows.toLong(), followers.toLong())
internal fun AudioQuality.sdkQuality() = SourceAudioQuality(name, label, when(this) {
    AudioQuality.LOSSLESS, AudioQuality.HI_RES -> "audio/flac"
    AudioQuality.DOLBY -> "audio/eac3"
    AudioQuality.HIGHER -> "audio/mp4"
    else -> "audio/mpeg"
})
internal fun List<TimedLyric>.sdkLyrics() = SourceLyrics(map { line -> SourceLyricLine(line.timeMs, line.text,
    line.words.map { SourceLyricWord(it.startMs, (it.endMs - it.startMs).coerceAtLeast(0), it.text) }, line.translation, line.transliteration, line.parserVersion) })
internal fun jsonStrings(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.optString(it) }
internal fun SourceCursorRequest.offset() = cursor?.toIntOrNull() ?: 0
internal fun SourceEntityRef.singer() = QqSearchSinger(id, name, null,
    if (payload.format == "qq.artist" && payload.encoded.isNotBlank()) JSONObject(payload.encoded).optLong("numericId") else 0)
internal fun QqSearchSinger.entity() = SourceEntity(SourceEntityRef(id, name, "artist", SourcePayload("qq.artist", 1,
    JSONObject().put("numericId", numericId).toString())), artwork = SourceArtwork(artwork))
